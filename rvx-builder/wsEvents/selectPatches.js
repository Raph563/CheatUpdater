const { writePatches } = require('../utils/Settings.js');

/**
 * @param {Record<string, any>} message
 */
module.exports = function selectPatches(message) {
  global.jarNames.includedPatches = [];
  global.jarNames.excludedPatches = [];

  writePatches(global.jarNames.selectedApp, message.selectedPatches);

  /** @type {string[]} */
  const includedPatchesArray = [];

  for (const patch of message.selectedPatches) {
    const patchName = patch.replace(/\|.+$/, '');

    includedPatchesArray.push(patchName);

    global.jarNames.includedPatches.push(`-i`);
    global.jarNames.includedPatches.push(patchName);
  }

  global.jarNames.isRooted = false;

  for (const patch of message.excludedPatches) {
    const patchName = patch.replace(/\|.+$/, '');

    if (includedPatchesArray.includes(patchName)) continue;

    if (patch.includes('MicroG support') || patch.includes('GmsCore support')) {
        global.jarNames.isRooted = true;
    }

    global.jarNames.excludedPatches.push(`-e`);
    global.jarNames.excludedPatches.push(patchName);
  }
};
