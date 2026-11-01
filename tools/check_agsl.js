// Compiles the viewfinder shader with Skia (the engine behind Android's AGSL).
const CK = require('canvaskit-wasm');
const sh = require(process.argv[2]);
CK().then(ck => {
  const errs = [];
  const eff = ck.RuntimeEffect.Make(sh.agsl, e => errs.push(e));
  if (eff) { console.log('AGSL OK (' + eff.getUniformCount() + ' uniform floats)'); process.exit(0); }
  for (const e of errs) for (const line of String(e).split('\n')) if (line.trim()) console.log('::error::AGSL ' + line);
  process.exit(1);
});
