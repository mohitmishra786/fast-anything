// fast-anything: ends the app after FAST_ANYTHING_STOP_AFTER_MS (scan --duration), so --cpu-prof
// writes the profile of what ran. Preloaded for that run only. Not meant to be used by hand.
const ms = Number(globalThis.process?.env?.FAST_ANYTHING_STOP_AFTER_MS);
let main = true;
try {
  main = require("node:worker_threads").isMainThread;
} catch {
  // no worker_threads: there's only the main thread
}
if (ms > 0 && main) setTimeout(() => process.exit(0), ms).unref();
