const { writeFileSync } = require('node:fs');
const { join: joinPath } = require('node:path');

const exec = require('./promisifiedExec.js');

/**
 * @param {string} pkg
 * @param {import('ws').WebSocket} ws
 */
module.exports = async function mountReVanced(pkg, ws) {
  // Create folder
  await exec('su -c \'mkdir -p "/data/adb/revanced/"\'');

  // Copy ReVanced APK to the folder **directly**
  await exec(
    `su -c 'cp "${joinPath(
      global.revancedDir,
      global.outputName
    )}" "/data/adb/revanced/"'`
  );

  // Unmount the already existing ReVanced APK, so it can be updated
  try {
    // Force stop the app
    await exec(`su -c 'am force-stop ${pkg}'`);
    // Unmount
    await exec(
      `su -mm -c 'stock_path="$(pm path ${pkg} | grep base | sed 's/package://g')" && umount -l "$stock_path"'`
    );
    // eslint-disable-next-line no-empty
  } catch {} // An error occured, probably because there is no existing ReVanced APK to be unmounted, ignore it and continue

  // Move APK to folder
  await exec(
    `su -c 'base_path="/data/adb/revanced/${pkg}.apk" && mv -f "/data/adb/revanced/${global.outputName}" "$base_path" && chmod 644 "$base_path" && chown system:system "$base_path" && chcon u:object_r:apk_data_file:s0 "$base_path"'`
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

  // Move Mount script to folder
  await exec(
    `su -c 'cp "./mount.sh" "/data/adb/service.d/mount_revanced_${pkg}.sh"'`
  );
  // Give execution perms to Mount script
  await exec(`su -c 'chmod +x "/data/adb/service.d/mount_revanced_${pkg}.sh"'`);

  // Run Mount script
  await exec(`su -mm -c '"/data/adb/service.d/mount_revanced_${pkg}.sh"'`);

  // Kill app process
  await exec(`su -c 'am force-stop ${pkg}'`);

  ws.send(
    JSON.stringify({
      event: 'patchLog',
      log: 'ReVanced should be now mounted! Please open the app and check if it has been mounted.'
    })
  );
};
