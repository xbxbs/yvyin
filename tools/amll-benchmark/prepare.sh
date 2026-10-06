#!/usr/bin/env bash
set -euo pipefail
script_dir="$(cd "$(dirname "$0")" && pwd)"
project_dir="${PROJECT_DIR:-$(cd "$script_dir/../.." && pwd)}"
work_dir="${BENCHMARK_WORK:-/root/amll-benchmark}"
benchmark_dir="${BENCHMARK_DIR:-/root/amll-benchmark/site}"
mkdir -p "$work_dir"
if [ "$script_dir" != "$work_dir" ]; then
    cp "$script_dir"/{package.json,main.js,run.cjs,index.html} "$work_dir/"
    if [ -f "$script_dir/package-lock.json" ]; then cp "$script_dir/package-lock.json" "$work_dir/"; fi
fi
cd "$work_dir"
npm install --no-audit --no-fund --legacy-peer-deps
mkdir -p "$benchmark_dir"
cp index.html "$benchmark_dir/"
cp "$project_dir/app/src/main/assets/track.json" "$benchmark_dir/"
cp "$project_dir/app/src/main/res/drawable-nodpi/cover.jpg" "$benchmark_dir/"
cp "$project_dir/app/src/main/res/drawable-nodpi/player_background.jpg" "$benchmark_dir/background.jpg"
cp "$project_dir/app/src/main/res/font/player_cjk_medium.ttf" "$benchmark_dir/"
BENCHMARK_DIR="$benchmark_dir" node -e 'require("esbuild").buildSync({entryPoints:["main.js"],bundle:true,format:"esm",outfile:process.env.BENCHMARK_DIR+"/bundle.js"})'
PLAYWRIGHT_DOWNLOAD_CONNECTION_TIMEOUT=240000 node node_modules/playwright/cli.js install --with-deps --only-shell chromium
BENCHMARK_DIR="$benchmark_dir" node run.cjs
