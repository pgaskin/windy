#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"

die() {
    echo "$@" >&2
    exit 1
}

rm -rf build
mkdir build

shopt -s nullglob
changelogs=../metadata/en-US/changelogs
files=("$changelogs"/*.txt)
shopt -u nullglob
test ${#files[@]} -gt 0 || die "no changelogs in $changelogs"

whatsnew=$(
    set -e
    indent='          '
    n=0
    for v in $(printf '%s\n' "${files[@]}" | sed 's|.*/||; s|\.txt$||' | sort -rn); do
        open=''
        if [ $n -lt 2 ]; then
            open=' open'
        fi
        if [ $n -eq 8 ]; then
            printf '%s<details class="more">\n' "$indent"
            printf '%s  <summary>More versions</summary>\n' "$indent"
            indent="$indent  "
        fi
        printf '%s<details class="release"%s>\n' "$indent" "$open"
        printf '%s  <summary>Version %s</summary>\n' "$indent" "$v"
        printf '%s  <ul>\n' "$indent"
        sed -e 's/&/\&amp;/g; s/</\&lt;/g; s/>/\&gt;/g' "$changelogs/$v.txt" | sed -n "s|^\* \(.*\)\$|$indent    <li>\1</li>|p"
        printf '%s  </ul>\n' "$indent"
        printf '%s</details>\n' "$indent"
        n=$((n + 1))
    done
    if [ $n -gt 8 ]; then
        printf '%s</details>\n' "${indent%  }"
    fi
)

whatsnew="$whatsnew" awk '
    /<!-- CHANGELOG -->/ { printf "%s\n", ENVIRON["whatsnew"]; found = 1; next }
    { print }
    END { if (!found) { print "no CHANGELOG placeholder in index.html" >"/dev/stderr"; exit 1 } }
' index.html >build/index.html
echo "build/index.html"

ua='Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36'
css=$(set -e; curl -fsS -A "$ua" 'https://fonts.googleapis.com/css2?family=Google+Sans:wght@400..700&display=swap')
url=$(set -e; awk '/\/\* latin \*\//{f=1} f && /src: url\(/{sub(/.*url\(/, ""); sub(/\).*/, ""); print; exit}' <<<"$css")
test -n "$url" || die 'no latin subset in the google fonts css'
curl -fsS -o build/font.woff2 "$url"
echo "build/font.woff2"

cargo run --locked --release --package windy-wallpaper-preview -- --website build
