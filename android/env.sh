# Toolchain environment for building Hex for Android. Source it: `. android/env.sh`.
# Every value can be overridden from the environment, or in env.local.sh
# beside this file (not committed) for a machine's own paths.
here="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
[ -f "$here/env.local.sh" ] && . "$here/env.local.sh"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.2.12479018}"
export PATH="${CARGO_HOME:-$HOME/.cargo}/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
