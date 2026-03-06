const { existsSync, rmSync } = require('node:fs');
const exec = require('../utils/promisifiedExec.js');

/**
 * @param {import('ws').WebSocket} ws
 */
module.exports = async function resetPatchOptions(ws) {
  if (existsSync('options.json')) {
    rmSync('options.json', { recursive: true, force: true });
  }

  const java = `${global.javaCmd}`;
  const cli = `${global.jarNames.cli}`;
  const patches = `${global.jarNames.patchesJar}`;

  await exec(
    `${java} -jar "${cli}" options --overwrite "${patches}"`
  );

  if (!existsSync('options.json')) {
    await exec(
      `${java} -jar "${cli}" options --overwrite -b "${patches}"`
    );
  }
};
