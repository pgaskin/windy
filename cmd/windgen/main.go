// Command windgen generates wind field images from GFS data.
package main

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"flag"
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
	"os"
	"os/exec"
	"os/signal"
	"strconv"
	"strings"
	"time"
)

var (
	Gribber = flag.String("gribber", "", "Path to gribber (github.com/noritada/grib-rs)")
	Time    = flag_Time("time", time.Now().UTC().Truncate(time.Second), "Time to use instead of the current time")
	Output  = flag.String("output", "wind_cache", "Output base path")
	Prev    = flag.Int("prev", 4, "Maximum number of previous cycles to check if the current one does not exist")
	Retry   = flag.Int("retry", 3, "Maximum number of retries per cycle")
)

func flag_Time(name string, value time.Time, usage string) *time.Time {
	v := new(time.Time)
	flag.TextVar(v, name, value, usage)
	return v
}

func main() {
	flag.Parse()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()

	if err := func() error {
		var (
			img   image.Image
			cycle = gfsCyclePrev(*Time)
		)
		slog.Info("looking for gfs data", "gfs_cycle", cycle)
	cycle:
		for prev := 0; ; prev++ {
		retry:
			for attempt := 0; ; attempt++ {
				select {
				case <-ctx.Done():
					return ctx.Err()
				default:
				}
				if x, err := generate(ctx, *Gribber, cycle); err != nil {
					switch {
					case errors.Is(err, fs.ErrNotExist):
						slog.Warn("no gfs data found", "gfs_cycle", cycle, "prev", prev, "error", err)
						if prev < *Prev {
							cycle = gfsCyclePrev(cycle)
							continue cycle
						}
						return fmt.Errorf("no gfs data found after %s (%d update cycles ago)", cycle, prev)
					default:
						slog.Warn("failed to get gfs data", "gfs_cycle", cycle, "attempt", attempt, "error", err)
						if attempt < *Retry {
							continue retry
						}
						return fmt.Errorf("failed to generate image (%d retries): %w", attempt, err)
					}
				} else {
					img = x
					break cycle
				}
			}
		}
		slog.InfoContext(ctx, "generated image", "size", img.Bounds().Size(), "gfs_cycle", cycle)

		var buf bytes.Buffer
		if err := png.Encode(&buf, img); err != nil {
			return fmt.Errorf("encode png: %w", err)
		}
		if err := os.WriteFile(*Output+".png", buf.Bytes(), 0644); err != nil {
			return fmt.Errorf("save png: %w", err)
		}
		slog.InfoContext(ctx, "saved image", "format", "png", "name", *Output+".png")

		buf.Reset()
		if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: 100}); err != nil {
			return fmt.Errorf("encode jpeg: %w", err)
		}
		if err := os.WriteFile(*Output+".jpg", buf.Bytes(), 0644); err != nil {
			return fmt.Errorf("save jpeg: %w", err)
		}
		slog.InfoContext(ctx, "saved image", "format", "png", "name", *Output+".jpg")

		return nil
	}(); err != nil {
		slog.ErrorContext(ctx, "error", "error", err)
		os.Exit(1)
	}
}

// gfsCycle formats t as the current GFS cycle.
func gfsCycle(t time.Time) (year, month, day, cycle int) {
	t = t.UTC()
	var m time.Month
	year, m, day = t.Date()
	month = int(m)
	cycle = t.Hour() / 6 * 6
	return
}

// gfsCyclePrev returns the time of the current GFS cycle, or the previous one
// if it's already one.
func gfsCyclePrev(t time.Time) time.Time {
	t = t.UTC()

	var cycle int
	if hour := t.Hour(); hour%6 == 0 {
		cycle = hour - 6
	} else {
		cycle = hour / 6 * 6
	}
	if cycle < 0 {
		t = t.AddDate(0, 0, -1)
		cycle += 24
	}

	year, month, day := t.Date()
	return time.Date(year, month, day, cycle, 0, 0, 0, time.UTC)
}

// generate generates a wind field image for use with the windy live wallpaper.
func generate(ctx context.Context, gribber string, t time.Time) (image.Image, error) {
	year, month, day, cycle := gfsCycle(t)

	uv, err := getWindGrib(ctx, gribber, year, month, day, cycle, 0.25, "850 mb")
	if err != nil {
		return nil, err
	}

	img := image.NewRGBA(image.Rect(0, 0, len(uv[0]), len(uv)))
	for latIdx := range uv {
		for lngIdx := range uv[latIdx] {
			s, u, v := decompose(uv[latIdx][lngIdx])
			img.Set(lngIdx, latIdx, color.RGBA{
				R: uint8(mapValue(u, -1, 1, 0, 255)),
				G: uint8(mapValue(v, -1, 1, 0, 255)),
				B: uint8(mapValue(s, 0, 30, 0, 255)),
				A: 255,
			})
		}
	}
	return img, nil
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

// getWindGrib gets current wind data at the specifed level and time from GFS
// data, which is downloaded from Amazon S3. If the grib does not exist, the
// returned error will match [fs.ErrNotExist].
func getWindGrib(ctx context.Context, gribber string, year, month, day, cycle int, prec float64, level string) ([][][2]float64, error) {
	prec2 := int(prec * 100)
	if prec*100-float64(prec2) != 0 {
		return nil, fmt.Errorf("precision must be at most two decimal places")
	}
	if 360*100%prec2 != 0 {
		return nil, fmt.Errorf("precision must divide evenly")
	}

	var (
		base = fmt.Sprintf("https://noaa-gfs-bdp-pds.s3.amazonaws.com/gfs.%04d%02d%02d/%02d/atmos/gfs.t%02dz.pgrb2.%dp%02d.anl", year, month, day, cycle, cycle, prec2/100, prec2%100)
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
