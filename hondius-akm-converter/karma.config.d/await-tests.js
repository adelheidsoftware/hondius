// The JS test bundle imports a WebAssembly module, which makes it load asynchronously: its tests register only once
// that module is ready, and that can be after the page has loaded. Karma starts the run when the page loads, and a
// run that starts before any test is registered reports none. The script written below is served ahead of the
// bundle and holds the start until the tests are there, or for thirty seconds at most.
//
// The leading semicolon is required: this file is appended to generated code that may end without one.
;(function () {
	const fs = require("fs");
	const path = require("path");
	const script = path.join(__dirname, "await-registered-tests.js");
	fs.writeFileSync(script, `(function () {
	var karma = window.__karma__;
	var start = karma.loaded.bind(karma);
	var since = Date.now();
	karma.loaded = function () {
		(function poll() {
			var suite = window.mocha && window.mocha.suite;
			var registered = suite && (suite.suites.length > 0 || suite.tests.length > 0);
			if (registered || Date.now() - since > 30000) start();
			else setTimeout(poll, 20);
		})();
	};
})();
`);
	config.files.unshift(script);
})();
