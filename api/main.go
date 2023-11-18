// Command windyapi generates and serves wind field images for the Windy live
// wallpaper.
package main

import (
	"context"
	"encoding"
	"flag"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"os"
	"os/signal"
	"strings"
	"sync"
)

const EnvPrefix = "WINDY"

var (
	Addr                = flag.String("addr", ":8080", "Listen address")
	LogLevel            = flag_TextVar[slog.Level]("log-level", 0, "Log level (info-1 includes http requests) (debug/info/warn/error)")
	LogJSON             = flag.Bool("log-json", false, "Output logs as JSON")
	ProxyHeader         = flag.String("proxy-header", "", "Trusted header containing the remote address (e.g., X-Forwarded-For)")
	Gribber             = flag.String("gribber", "gribber", "Path to gribber executable (github.com/noritada/grib-rs) (tested with 0.7.1)")
	WindResponseTimeout = flag.Duration("wind-response-timeout", 0, "Override the default response timeout, after which old data is served if available (negative for no limit)")
	WindGFS             = flag.String("wind-gfs", "", "Override the default GFS forecast data mirror")
	WindGFSPrecision    = flag.Float64("wind-gfs-precision", 0, "Override GFS data lng/lat grid precision to use")
	WindGFSLevel        = flag.String("wind-gfs-level", "", "Override GFS wind data elevation to use")
	WindTimeout         = flag.Duration("wind-timeout", 0, "Override the default timeout for an update")
	WindFetchTimeout    = flag.Duration("wind-fetch-timeout", 0, "Override the default timeout for fetching a single cycle of wind data (negative for no limit)")
	WindMaxPrevCycles   = flag.Int("wind-max-prev-cycles", 0, "Override the default maximum number of previous wind data cycles to try if the current one isn't available (negative for no limit)")
	WindMaxRetry        = flag.Int("wind-max-retry", 0, "Override the default maximum number of times to attempt to fetch a single wind data cycle if isn't non-existent (negative for no limit)")
	Once                = flag.Bool("once", false, "Dump the wind field images to the current directory instead of starting the server")
)

func flag_TextVar[T encoding.TextMarshaler](name string, value T, usage string) *T {
	v := new(T)
	flag.TextVar((any)(v).(encoding.TextUnmarshaler), name, value, usage)
	return v
}

func main() {
	// parse config
	flag.CommandLine.Usage = func() {
		fmt.Fprintf(flag.CommandLine.Output(), "usage: %s [options]\n", flag.CommandLine.Name())
		fmt.Fprintf(flag.CommandLine.Output(), "\noptions:\n")
		flag.CommandLine.PrintDefaults()
		fmt.Fprintf(flag.CommandLine.Output(), "\nnote: all options can be specified as environment variables with the prefix %q and dashes replaced with underscores\n", EnvPrefix)
	}
	for _, e := range os.Environ() {
		if e, ok := strings.CutPrefix(e, EnvPrefix+"_"); ok {
			if k, v, ok := strings.Cut(e, "="); ok {
				if err := flag.CommandLine.Set(strings.ReplaceAll(strings.ToLower(k), "_", "-"), v); err != nil {
					fmt.Fprintf(flag.CommandLine.Output(), "env %s: %v\n", k, err)
					flag.CommandLine.Usage()
					os.Exit(2)
				}
			}
		}
	}
	if flag.Parse(); flag.NArg() > 1 {
		fmt.Fprintf(flag.CommandLine.Output(), "incorrect number of arguments %q provided\n", flag.Args())
		flag.CommandLine.Usage()
		os.Exit(2)
	}

	// setup slog if required
	var logOptions *slog.HandlerOptions
	if *LogLevel != 0 {
		logOptions = &slog.HandlerOptions{
			Level: *LogLevel,
		}
	}
	if *LogJSON {
		slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, logOptions)))
	} else if logOptions != nil {
		slog.SetDefault(slog.New(slog.NewTextHandler(os.Stdout, logOptions)))
	}

	// setup wind field
	windy := &Windy{
		Gribber:         *Gribber,
		GFSPrecision:    *WindGFSPrecision,
		GFSLevel:        *WindGFSLevel,
		ResponseTimeout: *WindResponseTimeout,
		Timeout:         *WindTimeout,
		FetchTimeout:    *WindFetchTimeout,
		MaxPrevCycles:   *WindMaxPrevCycles,
		MaxRetry:        *WindMaxRetry,
	}
	if *WindGFS != "" {
		if gfs, err := url.Parse(*WindGFS); err != nil {
			slog.Error("invalid gfs url", "error", err)
			os.Exit(1)
		} else if !gfs.IsAbs() {
			slog.Error("invalid gfs url", "error", "not absolute")
			os.Exit(1)
		} else {
			windy.GFS = *gfs
		}
	}

	// setup http
	srv := &http.Server{
		Addr: *Addr,
		Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if p, ok := strings.CutPrefix(r.URL.Path, "/"); ok {
				if !strings.ContainsRune(p, '/') {
					if strings.HasPrefix(p, "wind_field.") || strings.HasPrefix(p, "wind_cache.") {
						switch r.Method {
						case http.MethodGet, http.MethodHead:
							windy.ServeHTTP(w, r)
						default:
							w.Header().Set("Allow", "HEAD, GET")
							http.Error(w, http.StatusText(http.StatusMethodNotAllowed), http.StatusMethodNotAllowed)
						}
						return
					}
				}
			}
			if r.URL.Path == "/" {
				http.Redirect(w, r, "https://github.com/pgaskin/windy", http.StatusTemporaryRedirect)
				return
			}
			http.Error(w, http.StatusText(http.StatusNotFound), http.StatusNotFound)
		}),
	}
	if *ProxyHeader != "" {
		next := srv.Handler
		srv.Handler = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if x, _, _ := strings.Cut(r.Header.Get(*ProxyHeader), ","); x != "" {
				r1 := *r
				r = &r1
				if xap, err := netip.ParseAddrPort(x); err == nil {
					// valid ip/port; keep the entire thing
					r.RemoteAddr = xap.String()
				} else if xa, err := netip.ParseAddr(x); err == nil {
					// only an ip; keep the existing port if possible
					eap, _ := netip.ParseAddrPort(r.RemoteAddr)
					r.RemoteAddr = netip.AddrPortFrom(xa, eap.Port()).String()
				} else {
					// invalid
					slog.Warn("failed to parse proxy remote ip header", "header", *ProxyHeader, "value", x)
				}
			}
			next.ServeHTTP(w, r)
		})
	}

	// handle interrupt
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()

	// start components
	var wg sync.WaitGroup

	// start wind field updater
	wg.Add(1)
	go func() {
		defer wg.Done()
		windy.Run(ctx)
	}()

	if *Once {
		// dump images
		slog.Info("waiting for wind field")
		data, err, err1 := windy.Data(ctx)
		if err1 == nil {
			err1 = err
		}
		if err != nil {
			slog.Error("failed to get wind field images", "error", err)
			os.Exit(1)
		}
		if err := os.WriteFile("wind_field.jpg", data.JPG.Data, 0644); err != nil {
			slog.Error("failed to save image", "error", err)
		}
		if err := os.WriteFile("wind_field.png", data.PNG.Data, 0644); err != nil {
			slog.Error("failed to save image", "error", err)
		}
		slog.Info("saved images")
	} else {
		// start http
		if sock, err := net.Listen("tcp", srv.Addr); err != nil {
			slog.Error("listen", "error", err)
			os.Exit(1)
		} else {
			slog.Info("serving http", "addr", srv.Addr)
			go srv.Serve(sock)
		}

		// wait for the server to be interrupted
		<-ctx.Done()
		slog.Info("received interrupt, shutting down")

		// wait for the server to shutdown until the next interrupt
		slog.Info("waiting for http server to shut down")
		srv.Shutdown(ctx)
	}

	// wait for other components; the next interrupt will immediately terminate
	// the server, regardless of whether the other components have shut down
	slog.Info("waiting for other components to shut down")
	stop()
	wg.Wait()

	// done
	slog.Info("stopped")
}
