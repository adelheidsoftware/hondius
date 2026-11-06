# Builds the wasm-bindgen package in pkg/, which is committed so that a build without a Rust toolchain works.
#
# rustc compiles the source path of every dependency into the panic messages of the binary. The remaps replace this
# machine's Cargo and rustup folders, and this one, with fixed names, so the committed binary carries no local path.

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$cargoHome = if ($env:CARGO_HOME) { $env:CARGO_HOME } else { Join-Path $HOME ".cargo" }
$rustupHome = if ($env:RUSTUP_HOME) { $env:RUSTUP_HOME } else { Join-Path $HOME ".rustup" }
$flags = @(
	"--remap-path-prefix=$cargoHome=/cargo",
	"--remap-path-prefix=$rustupHome=/rustup",
	"--remap-path-prefix=$PSScriptRoot=/src"
)
# The encoded form separates flags with U+001F, so a path that contains a space stays one flag
$env:CARGO_ENCODED_RUSTFLAGS = $flags -join [char]0x1f

wasm-pack build --target bundler --release
if ($LASTEXITCODE -ne 0) {
	exit $LASTEXITCODE
}

# wasm-pack writes a .gitignore into pkg/ that would hide the package from git
Remove-Item (Join-Path $PSScriptRoot "pkg/.gitignore") -ErrorAction SilentlyContinue
