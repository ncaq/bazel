// Copyright 2023 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.bazel.bzlmod;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableMap;
import com.google.common.eventbus.Subscribe;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.LockfileMode;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Module collecting Bazel module and module extensions resolution results and updating the
 * lockfile.
 */
public class BazelLockFileModule extends BlazeModule {

  private Path workspaceRoot;
  @Nullable private BazelModuleResolutionEvent moduleResolutionEvent;
  private final List<ModuleExtensionResolutionEvent> extensionResolutionEvents = new ArrayList<>();

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  @Override
  public void beforeCommand(CommandEnvironment env) {
    workspaceRoot = env.getWorkspace();
    RepositoryOptions options = env.getOptions().getOptions(RepositoryOptions.class);
    if (options.lockfileMode.equals(LockfileMode.UPDATE)) {
      env.getEventBus().register(this);
    }
  }

  @Override
  public void afterCommand() throws AbruptExitException {
    if (moduleResolutionEvent == null && extensionResolutionEvents.isEmpty()) {
      return; // nothing changed, do nothing!
    }

    RootedPath lockfilePath =
        RootedPath.toRootedPath(Root.fromPath(workspaceRoot), LabelConstants.MODULE_LOCKFILE_NAME);

    // Create an updated version of the lockfile with the events updates
    BazelLockFileValue lockfile;
    if (moduleResolutionEvent != null) {
      lockfile = moduleResolutionEvent.getLockfileValue();
    } else {
      // Read the existing lockfile (if none exists, will get an empty lockfile value)
      try {
        lockfile = BazelLockFileFunction.getLockfileValue(lockfilePath);
      } catch (IOException | JsonSyntaxException | NullPointerException e) {
        logger.atSevere().withCause(e).log(
            "Failed to read and parse the MODULE.bazel.lock file with error: %s."
                + " Try deleting it and rerun the build.",
            e.getMessage());
        return;
      }
    }
    lockfile =
        lockfile.toBuilder()
            .setModuleExtensions(combineModuleExtensions(lockfile.getModuleExtensions()))
            .build();

    // Write the new value to the file
    updateLockfile(lockfilePath, lockfile);
    this.moduleResolutionEvent = null;
    this.extensionResolutionEvents.clear();
  }

  /**
   * Combines the old extensions stored in the lockfile -that are still used- with the new
   * extensions from the events (if any)
   *
   * @param oldModuleExtensions Module extensions stored in the current lockfile
   */
  private ImmutableMap<LockFileModuleExtensionKey, LockFileModuleExtension> combineModuleExtensions(
      ImmutableMap<LockFileModuleExtensionKey, LockFileModuleExtension> oldModuleExtensions) {
    //for each updated extension, a map of its id and keys
    ImmutableMap<ModuleExtensionId, LockFileModuleExtensionKey> extensionFromEvents =
        ImmutableMap.copyOf(extensionResolutionEvents.stream()
            .collect(
                ImmutableMap.toImmutableMap(
                    e -> e.getExtensionKey().getExtensionId(),
                    ModuleExtensionResolutionEvent::getExtensionKey
                )
            ));

    ImmutableMap.Builder<LockFileModuleExtensionKey, LockFileModuleExtension> updatedExtensionMap =
        ImmutableMap.builder();
    for (Map.Entry<LockFileModuleExtensionKey, LockFileModuleExtension> extensionEntry :
        oldModuleExtensions.entrySet()) {
      LockFileModuleExtensionKey key = extensionEntry.getKey();
      if (shouldKeepExtension(key, extensionFromEvents.get(key.getExtensionId()))) {
        updatedExtensionMap.put(extensionEntry);
      }
    }

    // Add the new resolved extensions
    for (ModuleExtensionResolutionEvent extensionEvent : extensionResolutionEvents) {
      updatedExtensionMap.put(extensionEvent.getExtensionKey(), extensionEvent.getModuleExtension());
    }
    return updatedExtensionMap.buildKeepingLast();
  }

  /**
   * Decide whether to keep this extension or not depending on both: 1. If it is still has a usage
   * in the module 2. If its attributes OS & arch didn't change
   *
   * @param lockedExtensionKey object holding the old extension id and state of os and arch
   * @param updatedExtensionKey object holding the updated extension id and state of os and arch,
   * its value is Null if the extension was not updated
   * @return True if this extension should still be in lockfile, false otherwise
   */
  private boolean shouldKeepExtension(LockFileModuleExtensionKey lockedExtensionKey,
      LockFileModuleExtensionKey updatedExtensionKey) {
    //If updatedExtensionKey is null, then this extension was not updated, no need to check it
    if(updatedExtensionKey != null) {
      boolean dependencyOnOsChanged =
          lockedExtensionKey.getOs().isEmpty() != updatedExtensionKey.getOs().isEmpty();
      boolean dependencyOnArchChanged =
          lockedExtensionKey.getArch().isEmpty() != updatedExtensionKey.getArch().isEmpty();
      if(dependencyOnOsChanged || dependencyOnArchChanged){
        return false;
      }
    }
    //If moduleResolutionEvent is null, then no usage has changed. So we don't need this check
    if(moduleResolutionEvent != null) {
      return moduleResolutionEvent.getExtensionUsagesById().containsRow(lockedExtensionKey.getExtensionId());
    }
    return true;
  }

  /**
   * Updates the data stored in the lockfile (MODULE.bazel.lock)
   *
   * @param lockfilePath Rooted path to lockfile
   * @param updatedLockfile The updated lockfile data to save
   */
  public static void updateLockfile(RootedPath lockfilePath, BazelLockFileValue updatedLockfile) {
    try {
      FileSystemUtils.writeContent(
          lockfilePath.asPath(),
          UTF_8,
          GsonTypeAdapterUtil.createLockFileGson(
                  lockfilePath
                      .asPath()
                      .getParentDirectory()
                      .getRelative(LabelConstants.MODULE_DOT_BAZEL_FILE_NAME))
              .toJson(updatedLockfile));
    } catch (IOException e) {
      logger.atSevere().withCause(e).log(
          "Error while updating MODULE.bazel.lock file: %s", e.getMessage());
    }
  }

  @Subscribe
  public void bazelModuleResolved(BazelModuleResolutionEvent moduleResolutionEvent) {
    this.moduleResolutionEvent = moduleResolutionEvent;
  }

  @Subscribe
  public void moduleExtensionResolved(ModuleExtensionResolutionEvent extensionResolutionEvent) {
    this.extensionResolutionEvents.add(extensionResolutionEvent);
  }
}
