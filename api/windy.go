package main

import (
	"bufio"
	"bytes"
	"cmp"
	"context"
	"crypto/sha1"
	"encoding/hex"
	"errors"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"
	"io"
	"io/fs"
	"log/slog"
	"math"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Windy manages and serves wind forecast images for the Windy live wallpaper.
type Windy struct {
	state lazyResultWaiter[*WindData]

	// Gribber executable (github.com/noritada/grib-rs). Tested with 0.7.1.
	Gribber string

	// The maximum amount of time to wait for an active update to complete
	// before serving old data. Negative for no limit.
	ResponseTimeout time.Duration

	// The GFS forecast data mirror.
	GFS url.URL

	// GFS lng/lat grid precision to fetch.
	GFSPrecision float64

	// GFS wind vector elevation to fetch.
	GFSLevel string

	// The maximum total amount of time to spend attempting to do an update.
	Timeout time.Duration

	// The maximum amount of time to spend on a single data fetch attempt.
	// Negative for no limit.
	FetchTimeout time.Duration

	// The maximum number of previous GFS cycles to check if the current one is
	// not ready. Negative for no limit.
	MaxPrevCycles int

	// The maximum number of retries to do for each cycle (if it isn't
	// non-existent) when updating the data. Negative for no limit.
	MaxRetry int
}

// WindData stores the current wind field data.
type WindData struct {
	JPG, PNG struct {
		Data []byte
		ETag string
	}
	Updated time.Time
	Cycle   gfsCycle
	Source  string
}

// ServeHTTP serves wind field images based on the request filename.
func (h *Windy) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	slog.Log(r.Context(), slog.LevelInfo-1, "handle wind field request", "component", "wind_http", slog.Group("request",
		"method", r.Method,
		"host", r.Host,
		"path", r.URL.Path,
		"user_agent", r.Header.Get("User-Agent"),
		"remote_addr", r.RemoteAddr,
		slog.Group("cache",
			"if_none_match", r.Header.Get("If-None-Match"),
			"if_modified_since", r.Header.Get("If-Modified-Since"),
		),
	))

	dataCtx := r.Context()

	if t := negZeroDef(h.ResponseTimeout, time.Second*2); t != 0 {
		var cancel func()
		dataCtx, cancel = context.WithTimeout(dataCtx, t)
		defer cancel()
	}

	data, err, _ := h.Data(dataCtx)

	w.Header().Set("Date", time.Now().UTC().Format(http.TimeFormat))
	if err != nil {
		w.Header().Set("X-Gfs-Refresh-Error", err.Error())
	}
	if data == nil {
		if err == nil {
			http.Error(w, "Initial data update not complete yet.", http.StatusServiceUnavailable)
		} else {
			http.Error(w, "No data available (last error: "+err.Error()+").", http.StatusServiceUnavailable)
		}
		return
	}
	w.Header().Set("X-Gfs-Source", data.Source)
	w.Header().Set("X-Gfs-Cycle", data.Cycle.String())
	w.Header().Set("Last-Modified", data.Updated.UTC().Format(http.TimeFormat))

	var buf []byte
	switch ext := path.Ext(r.URL.Path); ext {
	case ".jpg":
		w.Header().Set("Content-Type", "image/jpeg")
		w.Header().Set("ETag", data.JPG.ETag)
		buf = data.JPG.Data
	case ".png":
		w.Header().Set("Content-Type", "image/png")
		w.Header().Set("ETag", data.PNG.ETag)
		buf = data.PNG.Data
	default:
		http.Error(w, "No image available for extension "+ext+".", http.StatusNotFound)
		return
	}
	http.ServeContent(w, r, "", data.Updated, bytes.NewReader(buf))
}

// Data gets the latest wind data, waiting up to ctx for the job to complete
// (returning old data if it doesn't). If the latest update job completed and
// failed, an error is also returned. The returned data must not be modified.
func (h *Windy) Data(ctx context.Context) (*WindData, error, error) {
	return h.state.Get(ctx)
}

// Run starts updating the wind data in the background, automatically adjusting
// to the forecast update schedule.
func (h *Windy) Run(ctx context.Context) {
	var (
		gribber       = zeroDef(h.Gribber, "gribber")
		gfs           = zeroDef(h.GFS, url.URL{Scheme: "https", Host: "noaa-gfs-bdp-pds.s3.amazonaws.com", Path: "/"})
		gfsPrecision  = zeroDef(h.GFSPrecision, 0.25)
		gfsLevel      = zeroDef(h.GFSLevel, "850 mb")
		timeout       = negZeroDef(h.Timeout, time.Second*100)
		fetchTimeout  = negZeroDef(h.FetchTimeout, time.Second*20)
		maxPrevCycles = negZeroDef(h.MaxPrevCycles, 3*4) // 3 days
		maxRetry      = negZeroDef(h.MaxRetry, 3)
		logger        = slog.Default().With("component", "wind_updater")
	)
	if !strings.HasSuffix(gfs.Path, "/") {
		gfs.Path += "/"
	}

	logger.InfoContext(ctx, "starting update worker", slog.Group("config",
		"gribber", gribber,
		"gfs", gfs.String(),
		"gfs_precision", gfsPrecision,
		"gfs_level", gfsLevel,
		"timeout", timeout,
		"fetch_timeout", fetchTimeout,
		"max_prev_cycles", maxPrevCycles,
		"max_retry", maxRetry,
	))

	update := time.NewTimer(0)
	defer update.Stop()

loop:
	for {
		select {
		case <-ctx.Done():
			logger.InfoContext(ctx, "update worker stopped")
			break loop
		case <-update.C:
		}
		logger.InfoContext(ctx, "updating wind field")

		if err := h.state.UpdateFunc(func() (*WindData, error) {
			ctx, cancel := context.WithTimeout(ctx, timeout)
			defer cancel()

			var output WindData
			output.Updated = time.Now()
			output.Cycle = gfsCycle(output.Updated)

			var wind [][][2]float64
		prev:
			for prev := 0; ; prev++ {
			retry:
				for retry := 0; ; retry++ {
					select {
					case <-ctx.Done():
						return nil, ctx.Err()
					default:
					}
					var err error

					output.Source, err = gfsPath(output.Cycle, gfsPrecision)
					if err != nil {
						return nil, fmt.Errorf("failed to generate gfs path: %w", err)
					}

					u := gfs
					u.Path += output.Source

					logger.Info("attempting to fetch wind data", "prev", prev, "retry", retry, "url", u.String())
					wind, err = getWindGrib(ctx, gribber, u.String(), gfsPrecision, gfsLevel)
					if err == nil {
						break prev
					}

					switch {
					case errors.Is(err, fs.ErrNotExist):
						logger.Warn("no gfs data found", "gfs_cycle", output.Cycle, "prev", prev, "error", err)
						if prev < maxPrevCycles {
							output.Cycle = output.Cycle.Prev()
							continue prev
						}
						return nil, fmt.Errorf("no gfs data found after %s (%d update cycles ago)", output.Cycle, prev)
					default:
						logger.Warn("failed to get gfs data", "gfs_cycle", output.Cycle, "attempt", retry, "error", err)
						if retry < maxRetry {
							continue retry
						}
						return nil, fmt.Errorf("failed to get gfs data (%d retries): %w", retry, err)
					}
				}
			}
			logger.Info("got wind data, generating image", slog.Group("data",
				"path", output.Source,
				"cycle", output.Cycle,
				slog.Group("wind",
					"prec", gfsPrecision,
					"level", gfsLevel,
					"lat", len(wind),
					"lng", len(wind[0]),
				),
			))

			img := image.NewRGBA(image.Rect(0, 0, len(wind[0]), len(wind)))
			for latIdx := range wind {
				for lngIdx := range wind[latIdx] {
					s, u, v := decompose(wind[latIdx][lngIdx])
					img.Set(lngIdx, latIdx, color.RGBA{
						R: uint8(mapValue(u, -1, 1, 0, 255)),
						G: uint8(mapValue(v, -1, 1, 0, 255)),
						B: uint8(mapValue(s, 0, 30, 0, 255)),
						A: 255,
					})
				}
			}
			logger.Info("generated image, encoding")

			var pngBuf bytes.Buffer
			if err := png.Encode(&pngBuf, img); err != nil {
				return nil, fmt.Errorf("encode png: %w", err)
			}
			output.PNG.Data = pngBuf.Bytes()

			var jpgBuf bytes.Buffer
			if err := jpeg.Encode(&jpgBuf, img, &jpeg.Options{Quality: 100}); err != nil {
				return nil, fmt.Errorf("encode png: %w", err)
			}
			output.JPG.Data = jpgBuf.Bytes()

			pngSha := sha1.Sum(output.PNG.Data)
			jpgSha := sha1.Sum(output.JPG.Data)

			output.PNG.ETag = "\"" + hex.EncodeToString(pngSha[:]) + "\""
			output.JPG.ETag = "\"" + hex.EncodeToString(jpgSha[:]) + "\""

			return &output, nil
		}); err != nil {
			logger.Error("failed to update wind field", "error", err)
		}

		// TODO: smarter update logic to try and sync with data updates?
		in := time.Hour
		update.Reset(in)
		logger.Info("scheduled next update", "in", in)
	}
}

// zeroDef returns def if val is zero, and val otherwise.
func zeroDef[T comparable](val, def T) T {
	var zero T
	if val == zero {
		return def
	}
	return val
}

// negZeroDef returns def if val is zero, zero if val is negative, and val
// otherwise.
func negZeroDef[T cmp.Ordered](val, def T) T {
	var zero T
	switch {
	case val == zero:
		return def
	case val < zero:
		return zero
	default:
		return val
	}
}

// decompose splits a vector into its magnitude and unit vector.
func decompose(vec [2]float64) (v float64, x float64, y float64) {
	v = math.Sqrt(vec[0]*vec[0] + vec[1]*vec[1])
	x = vec[0] / v
	y = vec[1] / v
	return
}

// mapValue clamps and maps value from one range to another.
func mapValue(value, inMin, inMax, outMin, outMax float64) float64 {
	if value < inMin {
		return outMin
	}
	if value > inMax {
		return outMax
	}
	return (((value - inMin) / (inMax - inMin)) * (outMax - outMin)) + outMin
}

// gfsCycle converts times to GFS cycles.
type gfsCycle time.Time

// Cycle returns the fields for the current cycle.
func (g gfsCycle) Cycle() (year, month, day, cycle int) {
	t := time.Time(g).UTC()
	year, m, day := t.Date()
	month = int(m)
	cycle = t.Hour() / 6 * 6
	return
}

// Prev returns next cycle less than the current one.
func (g gfsCycle) Prev() gfsCycle {
	year, month, day, cycle := g.Cycle()
	if cycle -= 6; cycle < 0 {
		day -= 1
		cycle += 24
	}
	return gfsCycle(time.Date(year, time.Month(month), day, cycle, 0, 0, 0, time.UTC).In(time.Time(g).Location()))
}

// String returns a human-readable description of the cycle.
func (g gfsCycle) String() string {
	year, month, day, cycle := g.Cycle()
	return fmt.Sprintf("%04d%02d%02d.%02d", year, month, day, cycle)
}

// gfsPath returns the path to a specific gfs atmospheric analysis data file
// with the most common parameters.
func gfsPath(g gfsCycle, prec float64) (string, error) {
	prec2, err := prec2(prec)
	if err != nil {
		return "", err
	}
	var (
		year, month, day, cycle = g.Cycle()
	)
	var (
		model      = "gfs"   // gfs
		collection = "atmos" // atmospheric
		variant    = "pgrb2" // most common parameters
		forecast   = "anl"   // analysis
	)
	return fmt.Sprintf("%s.%04d%02d%02d/%02d/%s/%s.t%02dz.%s.%dp%02d.%s",
		model, year, month, day,
		cycle,
		collection,
		model, cycle, variant, prec2/100, prec2%100, forecast,
	), nil
}

// getWindGrib gets current wind data at the specified level and time. If the
// grib does not exist, the returned error will match [fs.ErrNotExist].
func getWindGrib(ctx context.Context, gribber string, base string, prec float64, level string) ([][][2]float64, error) {
	if _, err := prec2(prec); err != nil {
		return nil, err
	}

	var (
		comp = [...][2]string{{"UGRD", level}, {"VGRD", level}}
		name = [...]string{"wind u-component", "wind v-componenet"}
	)

	idx, err := gribIndex(ctx, base, comp[:]...)
	if err != nil {
		return nil, fmt.Errorf("read grib index for %q: %w", base, err)
	}
	for c, comp := range comp {
		if idx[c] == [2]int{} {
			return nil, fmt.Errorf("read grib index for %q: %s %q for level %q not found", name[c], comp, base, level)
		}
	}

	var (
		latDim   = int(180/prec + 1)
		lngDim   = int(360 / prec)
		outValue = make([][][len(comp)]float64, latDim)
		outPoint = make([][][len(comp)]bool, latDim)
		outCount [len(comp)]int
	)
	for i := range outValue {
		outValue[i] = make([][len(comp)]float64, lngDim)
		outPoint[i] = make([][len(comp)]bool, lngDim)
	}
	for c, comp := range comp {
		data, err := gribData(ctx, base, idx[c])
		if err != nil {
			return nil, fmt.Errorf("read grib %s %q from %q %v: %w", name[c], comp, base, idx[c], err)
		}
		if err := gribValues(ctx, gribber, data,
			func(lat, lng, val float64) error {
				lng = math.Mod(lng+180, 360) - 180 // 0-360 -> -180-180
				latIdx := int(math.Round((-lat + 90) / prec))
				lngIdx := int(math.Round((lng + 180) / prec))
				if outPoint[latIdx][lngIdx][c] {
					return fmt.Errorf("duplicate point (%v, %v) -> (%d, %d)", lat, lng, latIdx, lngIdx)
				}
				outValue[latIdx][lngIdx][c] = val
				outPoint[latIdx][lngIdx][c] = true
				outCount[c]++
				return nil
			},
		); err != nil {
			return nil, fmt.Errorf("parse grib %s %q from %q %v: %w", name[c], comp, base, idx[c], err)
		}
		if outCount[c] != latDim*lngDim {
			return nil, fmt.Errorf("parse grib %s %q from %q %v: expected %d points for %dx%d grid (prec %.2f), got %d", name[c], comp, base, idx[c], latDim*lngDim, latDim, lngDim, prec, outCount[c])
		}
	}
	return outValue, nil
}

// prec2 checks and converts a 2-decimal-place lng/lat grid precision to an int.
func prec2(prec float64) (prec2 int, err error) {
	prec2 = int(prec * 100)
	switch {
	case prec*100-float64(prec2) != 0:
		err = fmt.Errorf("precision must be at most two decimal places")
	case 360*100%prec2 != 0:
		err = fmt.Errorf("precision must divide evenly")
	}
	return
}

// gribIndex finds the specified components (code, level) in the index for the
// provided grib file. If a component does not exist, the returned range will be
// zero.
func gribIndex(ctx context.Context, base string, components ...[2]string) ([][2]int, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, base+".idx", nil)
	if err != nil {
		return nil, err
	}

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusNotFound {
		return nil, fs.ErrNotExist
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("response status %d (%q)", resp.StatusCode, resp.Status)
	}

	var (
		cur = -1
		idx = make([][2]int, len(components))
		sc  = bufio.NewScanner(resp.Body)
	)
	for sc.Scan() {
		if sc.Text() == "" {
			continue
		}
		f := strings.Split(sc.Text(), ":")
		if len(f) != 7 {
			return idx, fmt.Errorf("parse: unexpected number of fields in line %q", sc.Text())
		}
		off, err := strconv.Atoi(f[1])
		if err != nil {
			return idx, fmt.Errorf("parse: invalid offset %q: %w", f[1], err)
		}
		if cur != -1 {
			idx[cur][1] = off - 1
			cur = -1
		}
		for i, c := range components {
			if c[0] == f[3] && c[1] == f[4] {
				cur = i
				idx[cur][0] = off
			}
		}
	}
	if err := sc.Err(); err != nil {
		return idx, err
	}
	return idx, nil
}

// gribData extracts a subset of the grib data from base. The server must
// support range requests.
func gribData(ctx context.Context, base string, subset [2]int) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, base, nil)
	if err != nil {
		return nil, err
	}
	if subset[1] == 0 {
		req.Header.Set("Range", "bytes="+strconv.Itoa(subset[0])+"-")
	} else {
		req.Header.Set("Range", "bytes="+strconv.Itoa(subset[0])+"-"+strconv.Itoa(subset[1]))
	}

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("get grib: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusNotFound {
		return nil, fmt.Errorf("get grib: %w", fs.ErrNotExist)
	}
	if resp.StatusCode == http.StatusOK {
		return nil, fmt.Errorf("get grib: server did not accept range request")
	}
	if resp.StatusCode != http.StatusPartialContent {
		return nil, fmt.Errorf("get grib: response status %d (%q)", resp.StatusCode, resp.Status)
	}

	buf, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("get grib: %w", err)
	}
	if !bytes.HasPrefix(buf, []byte{'G', 'R', 'I', 'B'}) {
		return nil, fmt.Errorf("get grib: response does not start with grib magic")
	}
	return buf, nil
}

// gribValues decodes float64 values from the provided grib data using the
// [gribber] tool.
//
// [gribber]: https://github.com/noritada/grib-rs
func gribValues(ctx context.Context, gribber string, data []byte, fn func(lat, lng, val float64) error) error {
	if gribber == "" {
		if p, err := exec.LookPath("gribber"); err != nil {
			return fmt.Errorf("find gribber executable: %w", err)
		} else {
			gribber = p
		}
	}

	ctx, cancel := context.WithCancel(ctx)
	defer cancel()

	f, err := os.CreateTemp("", "gribber-")
	if err != nil {
		return fmt.Errorf("make temp file: %w", err)
	}
	defer os.Remove(f.Name())

	if _, err := f.Write(data); err != nil {
		return fmt.Errorf("make temp file: %w", err)
	}
	if err := f.Close(); err != nil {
		return fmt.Errorf("make temp file: %w", err)
	}

	cmd := exec.CommandContext(ctx, gribber, "decode", f.Name(), "0.0")

	var stderr bytes.Buffer
	cmd.Stderr = &stderr

	var sc *bufio.Scanner
	if stdout, err := cmd.StdoutPipe(); err != nil {
		return err
	} else {
		sc = bufio.NewScanner(stdout)
	}
	if err := cmd.Start(); err != nil {
		return err
	}

	var i int
	for sc.Scan() {
		f := strings.Fields(sc.Text())
		if i++; i == 1 {
			for fie, fne := range []string{"Latitude", "Longitude", "Value"} {
				if fie >= len(f) || f[fie] != fne {
					return fmt.Errorf("expected field %d:%q, got %q", fie, fne, f)
				}
			}
			continue
		}
		lat, err := strconv.ParseFloat(f[0], 64)
		if err != nil {
			return fmt.Errorf("failed to parse latitude %q: %w", f[0], err)
		}
		lng, err := strconv.ParseFloat(f[1], 64)
		if err != nil {
			return fmt.Errorf("failed to parse longitude %q: %w", f[1], err)
		}
		val, err := strconv.ParseFloat(f[2], 64)
		if err != nil {
			return fmt.Errorf("failed to parse value %q: %w", f[2], err)
		}
		if err := fn(lat, lng, val); err != nil {
			return err
		}
	}
	if err := sc.Err(); err != nil {
		return fmt.Errorf("%w (stderr: %q)", err, stderr.String())
	}
	if i == 0 {
		return fmt.Errorf("no data returned (stderr: %q)", stderr.String())
	}
	return nil
}

// lazyResultWaiter waits for updaters to update a result, returning the old
// result if it doesn't finish in time or errors.
type lazyResultWaiter[T any] struct {
	initOnce sync.Once
	initDone sync.Once
	init     chan struct{}

	mu   sync.RWMutex
	wait chan struct{}
	res  T
	err  error
}

// Get waits for the update to complete or ctx to be cancelled, then returns the
// result. The returned value should not be modified (it is shared amongst all
// callers of Get).
func (w *lazyResultWaiter[T]) Get(ctx context.Context) (T, error, error) {
	select {
	case <-ctx.Done():
		var z T
		return z, nil, ctx.Err()
	case <-w.initCh():
	}

	w.mu.RLock()
	wait := w.wait
	w.mu.RUnlock()

	select {
	case <-ctx.Done():
	case <-wait:
	}

	w.mu.RLock()
	defer w.mu.RUnlock()
	return w.res, w.err, ctx.Err()
}

// Update begins an update. If it is called during another update, it will
// replace it. To avoid bugs, the result passed to the callback must not be
// shared with the previous one (i.e., if it's a pointer, it should be a new
// one). If an error is passed to the callback, the result will be ignored.
func (w *lazyResultWaiter[T]) Update() func(T, error) {
	wait := make(chan struct{})

	w.mu.Lock()
	w.wait = wait
	w.mu.Unlock()

	w.initDone.Do(func() {
		close(w.initCh())
	})

	var called bool
	return func(res T, err error) {
		w.mu.Lock()
		if w.wait == wait {
			if called {
				panic("update callback called multiple times")
			}
			called = true
			if err != nil {
				w.err = err
			} else {
				w.res = res
				w.err = nil
			}
			close(wait)
		}
		w.mu.Unlock()
	}
}

// UpdateFunc is a convenience wrapper around Update.
func (w *lazyResultWaiter[T]) UpdateFunc(fn func() (T, error)) error {
	var (
		res T
		err error
	)
	done := w.Update()
	func() {
		defer func() {
			if panic := recover(); panic != nil {
				err = fmt.Errorf("panic: %v", panic)
			}
		}()
		res, err = fn()
	}()
	done(res, err)
	return err
}

func (w *lazyResultWaiter[T]) initCh() chan struct{} {
	w.initOnce.Do(func() {
		w.init = make(chan struct{})
	})
	return w.init
}
