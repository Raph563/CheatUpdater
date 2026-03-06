const fetch = require('node-fetch');

const USER_AGENT_HEADERS = {
    headers: {
        'User-Agent': 'APKUpdater-v3.0.0'
        }
    }

module.exports = async function fetchWithUserAgent(url) {
  return fetch(url, USER_AGENT_HEADERS);
};
