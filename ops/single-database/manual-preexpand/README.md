# Reviewed WAR pre-expansion for Tomcat 11

The helper is a deployment candidate for the current `codex-lab-tomcat.service` and `/var/lib/codex-validation/tomcat-atomic/webapps`. It does not start or stop any service, modify a WAR, or touch a database. Without `--execute`, it only prints a plan and makes no file or network changes.

Tomcat 11.0.27's `ExpandWar` returns an existing directory if its tracker is missing or has the same millisecond modification time as the WAR. That check alone does not prove that the directory's contents match the WAR. This helper compares every extracted file to the exact reviewed WAR, rejects extra files, symlinks, dangerous paths, duplicate and case-colliding names, path collisions, ZIP bombs and unexpected embedded Context overrides. It sets an empty `META-INF/war-tracker` to the WAR's modification time and verifies it again. Source: <https://raw.githubusercontent.com/apache/tomcat/11.0.27/java/org/apache/catalina/startup/ExpandWar.java>. The Host setting is documented at <https://tomcat.apache.org/tomcat-11.0-doc/config/host.html>.

The only server.xml change is `Host` `unpackWARs="false"` to `"true"`, preserving the rest of the file. The original, which may contain secrets, is stored in an owner-private root preimage directory and must never be printed or published. If a target expanded directory already matches every file, its tracker, owner and permissions, it is reused. An unmatched root-owned, non-writable tree is moved intact to the private preimage directory; it is never recursively deleted. An unexpected path or unsafe owner is refused. A failed or interrupted run is **not complete**: retain its stage directory and preimages, keep Tomcat stopped, and inspect before deciding how to proceed. Do not blindly repeat.

## Required operator safeguards

This helper does not establish an exclusive lock or safely reconcile an interrupted run by itself. A single operator must hold an exclusive maintenance window, stop and runtime-mask the entry, checker, and Tomcat on both hosts, verify the effective masks and recursively empty service cgroups, and prevent automation from unmasking or starting the services. Do not run concurrent deployments. A privileged administrator can override these measures. Before any later run, inspect every existing intent, stage, preimage and original worker or systemd job; reconcile an unknown original result before deciding what to do. Never rerun blindly under a new UUID. Preserve private preimages until independently verified.

## Manual deployment sequence

1. Back up the current system and verify the recovery evidence. Confirm which five WARs are the newly reviewed releases, and verify each SHA-256. Stage them through the reviewed WAR deployment procedure. This helper reads the WARs currently in `webapps`; it does not infer them from the historical `release-atomic` directory.
2. Stop the normal entry and Tomcat following the current operations guide. Verify Tomcat's unit is inactive, `MainPID` and `ControlPID` are zero, and its cgroup is empty. Ensure no administrator or service modifies the deployment while this procedure runs. The helper also checks these conditions.
3. On each server separately, copy this script to a root-owned private staging path and verify its SHA-256 against the reviewed release. Create a **new**, root-owned mode-0600 manifest file containing only:

   ```json
   {"wars":{"ROOT.war":"<64-lowercase-hex>","Investment_and_Financial_Management.war":"<64-lowercase-hex>","starhub.war":"<64-lowercase-hex>","ucost.war":"<64-lowercase-hex>","atlas.war":"<64-lowercase-hex>"}}
   ```

   Replace each placeholder with the actual independently reviewed SHA-256. Verify the five installed `webapps` WARs match these values. Record the manifest's SHA-256 and a new UUID; never reuse an uncertain run ID. The same manifest can only be used on both hosts if the reviewed installed WAR hashes are identical.
4. Review the no-effect plan:

   ```sh
   sudo python3 -B /absolute/path/preexpand_wars.py
   ```

5. Run the explicit operation once, substituting the private manifest path, its verified hash and the fresh run UUID:

   ```sh
   sudo python3 -B /absolute/path/preexpand_wars.py --execute --run <UUID> --manifest /absolute/path/private-manifest.json --manifest-sha256 <MANIFEST-SHA256>
   ```

6. Require `VERIFIED_TOMCAT_STOPPED`. Keep the private preimages until the new deployment has been independently verified and a safe rollback is no longer needed. Do not publish the server.xml preimage. Before starting, verify all five WARs and expanded trees again and ensure no stale staging directory or unexpected Context file exists. Never rely on tracker timestamps alone.
7. Start Tomcat and the entry in the current documented order. Check the actual processes and readiness of the four admission-checked applications and the ATLAS static route, the exact loaded deployment resources, authenticated business reads, application writes as authorised, and actual startup time. This helper itself does not verify a Tomcat start, provider availability, application function, data consistency or performance. Repeat all checks on both servers; only then open normal service.
8. If reverting, stop entry and Tomcat, inspect the private preimage and currently installed files, preserve any new data/configuration, and restore a previously verified matching WAR/tree/server.xml set. Do not reactivate an old directory beside a different WAR. Revalidate tracker, full content hashes and access permissions before restarting. Cleanup of the private preimages is a separate, reviewed action.

Directories are root-owned, group `tomcat`, mode 0750; files are root-owned, group `tomcat`, mode 0640. The helper keeps physical disk free at or above 4,000,000,000 bytes after accounting for the uncompressed WAR contents, without claiming that privileged processes cannot consume reserved space.
