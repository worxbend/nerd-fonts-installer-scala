#!/bin/sh
# Install nerd-fonts-installer from GitHub Releases.
#
# Usage:
#   curl --proto '=https' --tlsv1.2 -sSfL \
#     https://github.com/worxbend/nerd-fonts-installer-scala/releases/latest/download/install.sh | sh
#
# Environment variables:
#   NERD_FONTS_INSTALLER_VERSION      Release tag to install, e.g. v0.1.0 (default: latest tagged release)
#   NERD_FONTS_INSTALLER_INSTALL_DIR  Directory to install the binary into (default: $HOME/.local/bin)

set -eu

REPO="worxbend/nerd-fonts-installer-scala"
VERSION="${NERD_FONTS_INSTALLER_VERSION:-latest}"
INSTALL_DIR="${NERD_FONTS_INSTALLER_INSTALL_DIR:-${HOME}/.local/bin}"

info() { printf '%s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }
require() { command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"; }

require curl
require tar
require uname
require mktemp

os="$(uname -s)"
case "${os}" in
  Linux) os=linux ;;
  Darwin) os=macos ;;
  *) die "unsupported OS: ${os} (Linux and macOS are supported)" ;;
esac

arch="$(uname -m)"
case "${arch}" in
  x86_64 | amd64) arch=amd64 ;;
  aarch64 | arm64) arch=arm64 ;;
  *) die "unsupported architecture: ${arch}" ;;
esac

target="${os}-${arch}"

if [ "${VERSION}" = "latest" ]; then
  info "resolving latest release version"
  latest_url="$(curl --proto '=https' --tlsv1.2 -fsSL -o /dev/null -w '%{url_effective}' \
    "https://github.com/${REPO}/releases/latest")"
  VERSION="${latest_url##*/}"
  [ -n "${VERSION}" ] || die "failed to resolve the latest release version"
fi

archive="nerd-fonts-installer_${VERSION}_${target}.tar.gz"
base="https://github.com/${REPO}/releases/download/${VERSION}"

tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT INT TERM

info "downloading ${archive}"
curl --proto '=https' --tlsv1.2 -fsSL -o "${tmp}/${archive}" "${base}/${archive}"
curl --proto '=https' --tlsv1.2 -fsSL -o "${tmp}/checksums.txt" "${base}/checksums.txt"

info "verifying checksum"
expected="$(grep " ${archive}\$" "${tmp}/checksums.txt" | awk '{print $1}')"
[ -n "${expected}" ] || die "no checksum entry for ${archive}"
if command -v sha256sum >/dev/null 2>&1; then
  actual="$(sha256sum "${tmp}/${archive}" | awk '{print $1}')"
else
  actual="$(shasum -a 256 "${tmp}/${archive}" | awk '{print $1}')"
fi
[ "${expected}" = "${actual}" ] || die "checksum mismatch for ${archive}"

tar -xzf "${tmp}/${archive}" -C "${tmp}"
mkdir -p "${INSTALL_DIR}"
install -m 0755 "${tmp}/${archive%.tar.gz}/nerd-fonts-installer" "${INSTALL_DIR}/nerd-fonts-installer"

info "installed ${INSTALL_DIR}/nerd-fonts-installer"
case ":${PATH}:" in
  *":${INSTALL_DIR}:"*) ;;
  *) info "add ${INSTALL_DIR} to your PATH, for example: export PATH=\"${INSTALL_DIR}:\$PATH\"" ;;
esac
"${INSTALL_DIR}/nerd-fonts-installer" --version
