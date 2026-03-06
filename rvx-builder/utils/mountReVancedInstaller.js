const { writeFileSync } = require('node:fs');
const { join: joinPath } = require('node:path');

const exec = require('./promisifiedExec.js');
const { spawn } = require('node:child_process');

async function promisifiedSpawn(command, args) {
  return new Promise((resolve, reject) => {
    const proc = spawn(command, args);
    proc.on('close', (code) => {
      resolve(code);
    });
    proc.on('error', (err) => {
      reject(err);
    });
  });
}

async function runCommand(command, deviceId) {
  return await promisifiedSpawn('adb', ['-s', deviceId, 'shell', command]);
}

/**
 * @param {string} pkg
 * @param {import('ws').WebSocket} ws
 * @param {string} deviceId
 */
module.exports = async function mountReVancedInstaller(deviceId) {
  let pkg = global.jarNames.selectedApp.packageName;

  // Copy ReVanced APK to temp.
  await exec(
    `adb -s ${deviceId} push "${joinPath(
      global.revancedDir,
      global.outputName
    )}" /data/local/tmp/revanced.delete`.trim()
  );
  // Create folder
  await runCommand('su -c \'mkdir -p "/data/adb/revanced/"\'', deviceId);

  // Prepare mounting
  await runCommand(
    `su -c 'base_path="/data/adb/revanced/${pkg}.apk" && mv /data/local/tmp/revanced.delete $base_path && chmod 644 $base_path && chown system:system $base_path && chcon u:object_r:apk_data_file:s0  $base_path'`,
    deviceId
  );

  // Create mount script
  writeFileSync(
    'mount.sh',
    `#!/system/bin/sh

    # Wait for the system to boot.
    until [ "$( getprop sys.boot_completed )" = 1 ]; do sleep 3; done
    until [ -d "/sdcard/Android" ]; do sleep 1; done

    # Unmount any existing mount as a safety measure.
    grep ${pkg} /proc/mounts | while read -r line; do echo $line | cut -d ' ' -f 2 | sed 's/apk.*/apk/' | xargs -r umount -l; done

    base_path="/data/adb/revanced/${pkg}.apk"
    stock_path=$(pm path ${pkg} | grep base | sed 's/package://g')
    am force-stop ${pkg}
    chcon u:object_r:apk_data_file:s0 $base_path
    [ ! -z "$stock_path" ] && mount -o bind $base_path $stock_path
    am force-stop ${pkg}`
  );

  // Push mount script to device
  await exec(
    `adb -s ${deviceId} push mount.sh /data/local/tmp/revanced.delete`
  );

  // Move Mount script to folder
  await runCommand(
    `su -c 'cp "/data/local/tmp/revanced.delete" "/data/adb/service.d/mount_revanced_${pkg}.sh"'`,
    deviceId
  );

  // Give execution perms to Mount script
  await runCommand(
    `su -c 'chmod +x "/data/adb/service.d/mount_revanced_${pkg}.sh"'`,
    deviceId
  );

  // Unmount apk for ...sanity?
  await runCommand(
    `su -c "stock_path=$( pm path ${pkg} | grep base | sed 's/package://g' ) && umount -l $stock_path"`,
    deviceId
  );

  // Run mount script
  await runCommand(
    `su -mm -c '"/data/adb/service.d/mount_revanced_${pkg}.sh"'`,
    deviceId
  );

  // Restart app
  await runCommand(
    `su -c 'monkey -p ${pkg} 1 && kill $(pidof -s ${pkg})'`,
    deviceId
  );
};
