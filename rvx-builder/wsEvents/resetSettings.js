const { resetPatchesSources } = require('../utils/Settings.js');

/**
 * @param {import('ws').WebSocket} ws
 */

module.exports = function resetSettings(ws) {
  resetPatchesSources(ws);
};
