// Enable WebAssembly module loading for the Karma test runner. Required because
// hondius-png (the Rust-backed wasm-bindgen package) imports a .wasm directly,
// and webpack 5 ships with WebAssembly support behind a feature flag.
//
// The Kotlin Gradle plugin also writes a plain `config.experiments = { topLevelAwait: true }`
// into the generated config, before or after this block depending on its version. Defining
// `experiments` via a getter/setter merges that assignment instead of letting it replace ours,
// so asyncWebAssembly survives. The leading semicolon is required: the plugin's assignment ends
// without one, and an opening parenthesis right after it would be parsed as a call on it.
;(function() {
	let __experiments = Object.assign({}, config.experiments || {}, {
		asyncWebAssembly: true,
		topLevelAwait: true,
	});
	Object.defineProperty(config, 'experiments', {
		configurable: true,
		enumerable: true,
		get() { return __experiments; },
		set(v) {
			__experiments = Object.assign({}, __experiments, v, { asyncWebAssembly: true });
		},
	});
})();
