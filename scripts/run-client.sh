#!/usr/bin/env bash
# Espetro client launcher with hybrid-GPU OpenGL workaround.
# NVIDIA kernel/userspace mismatch on this machine breaks default GLX;
# force Intel Mesa so GLFW can create a context.
set -euo pipefail
cd "$(dirname "$0")/.."

export __GLX_VENDOR_LIBRARY_NAME="${__GLX_VENDOR_LIBRARY_NAME:-mesa}"
export DRI_PRIME="${DRI_PRIME:-1}"
export __EGL_VENDOR_LIBRARY_FILENAMES="${__EGL_VENDOR_LIBRARY_FILENAMES:-/usr/share/glvnd/egl_vendor.d/50_mesa.json}"

# Fallback: software renderer if hardware GL still fails.
if ! glxinfo -B >/dev/null 2>&1; then
  echo "[run-client] hardware GL check failed, trying LIBGL_ALWAYS_SOFTWARE=1" >&2
  export LIBGL_ALWAYS_SOFTWARE=1
fi

echo "[run-client] OpenGL env:"
echo "  __GLX_VENDOR_LIBRARY_NAME=$__GLX_VENDOR_LIBRARY_NAME"
echo "  DRI_PRIME=$DRI_PRIME"
glxinfo -B 2>/dev/null | grep -E 'OpenGL renderer|OpenGL version' || true

exec ./gradlew runClient "$@"
