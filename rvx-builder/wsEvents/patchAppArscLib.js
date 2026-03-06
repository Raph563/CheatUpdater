const { spawn } = require('node:child_process');
const { version } = require('node:os');
const { existsSync, renameSync } = require('node:fs');
const { join, sep: separator } = require('node:path');

const exec = require('../utils/promisifiedExec.js');

const resetPatchOptions = require('./resetPatchOptions.js');

/**
 * @param {import('ws').WebSocket} ws
 */
async function afterBuild(ws) {
  outputName();
  renameSync(
    join(global.revancedDir, 'base.apk'),
    join(global.revancedDir, global.outputName)
  );

  if (process.platform === 'android') {
    await exec(
      `cp "${join(
        global.revancedDir,
        global.outputName
      )}" "/storage/emulated/0/${global.outputName}"`
    );

    ws.send(
      JSON.stringify({
        event: 'patchLog',
        log: `Copied files over to /storage/emulated/0/!\nPlease install ReVanced, its located in /storage/emulated/0/${global.outputName}.`
      })
    );
  }
  else if (!(global.jarNames.devices && global.jarNames.devices[0]))
    ws.send(
      JSON.stringify({
        event: 'patchLog',
        log: `ReVanced has been built!\nPlease transfer over revanced/${global.outputName}.`
      })
    );

  if (global.jarNames.devices && global.jarNames.devices[0]) {
    ws.send(JSON.stringify({ event: 'buildFinished', install: true }));
  } else ws.send(JSON.stringify({ event: 'buildFinished' }));
}

async function reinstallReVanced() {
  let pkgNameToGetUninstalled;

  switch (global.jarNames.selectedApp.packageName) {
    case 'com.reddit.frontpage':
      pkgNameToGetUninstalled = 'com.reddit.frontpage';
      break;
  }

  await exec(
    `adb -s ${global.jarNames.deviceID} uninstall ${pkgNameToGetUninstalled}`
  );
  await exec(
    `adb -s ${global.jarNames.deviceID} install ${join(
      global.revancedDir,
      global.outputName
    )}`
  );
}

function outputName() {
  const part1 = 'ReVanced';
  let part2 = global.jarNames?.selectedApp?.appName
    ? global.jarNames.selectedApp.appName.replace(/[^a-zA-Z0-9\\.\\-]/g, '')
    : global?.jarNames?.packageName
    ? global.jarNames.packageName.replace(/\./g, '')
    : ''; // make the app name empty if we cannot detect it

  // TODO: If the existing input APK is used from revanced/ without downloading, version and arch aren't set
  const part3 = global?.apkInfo?.version ? `v${global.apkInfo.version}` : '';
  const part4 = global?.apkInfo?.arch;
  const part5 = `cli_${global.jarNames.cli
    .split(separator)
    .at(-1)
    .replace('revanced-cli-', '')
    .replace('.jar', '')}`;
  const part6 = `patches_${global.jarNames.patchesJar
    .split(separator)
    .at(-1)
    .replace('revanced-patches-', '')
    .replace('.jar', '')}`;

  // Filename: ReVanced-<AppName>-<AppVersion>-[Arch]-cli_<CLI_Version>-patches_<PatchesVersion>.apk
  let outputName = '';

  for (const part of [part1, part2, part3, part4, part5, part6])
    if (part) outputName += `-${part}`;

  outputName += '.apk';

  global.outputName = outputName.substring(1);
}

/**
 * @param {string[]} args
 * @param {import('ws').WebSocket} ws
 */
function reportSys(args, ws) {
  ws.send(
    JSON.stringify({
      event: 'error',
      error:
        'An error occured while starting the patching process. Please see the server console.'
    })
  );

  console.log(
    '[builder] Please report these informations to https://github.com/inotia00/rvx-builder/issues'
  );
  console.log(
    `OS: ${process.platform}\nArguements: ${args.join(
      ', '
    )}\n OS Version${version()}`
  );
}

/**
 * @param {import('ws').WebSocket} ws
 */
module.exports = async function patchAppArscLib(ws) {
  if (!existsSync('options.json')) {
    resetPatchOptions(ws);
  }

  /** @type {string[]} */
  const args = [
    '-jar',
    global.jarNames.cli,
    '-b',
    global.jarNames.patchesJar,
    '-m',
    global.jarNames.integrations,
    '--options',
    './options.json',
    '--experimental',
    '-a',
    `${join(global.revancedDir, global.jarNames.selectedApp.packageName)}.apk`,
    '-o',
    'revanced'
  ];

  args.push(...global.jarNames.includedPatches);
  args.push(...global.jarNames.excludedPatches);

  const buildProcess = spawn(global.javaCmd, args);

  buildProcess.stdout.on('data', async (data) => {
    ws.send(
      JSON.stringify({
        event: 'patchLog',
        log: data.toString()
      })
    );

    if (data.toString().includes('Finished')) await afterBuild(ws);

    if (data.toString().includes('INSTALL_FAILED_UPDATE_INCOMPATIBLE')) {
      await reinstallReVanced(ws);
      await afterBuild(ws);
    }

    if (data.toString().includes('Unmatched')) reportSys(args, ws);
  });
};
