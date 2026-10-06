#!/bin/sh
exec /usr/bin/qemu-x86_64 -L /usr/x86_64-linux-gnu /root/tools/aapt2-8.13/aapt2 "$@"
