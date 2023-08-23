// Copyright 2021 The Bazel Authors. All rights reserved.
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

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.ExtendedEventHandler.Postable;
import com.ryanharter.auto.value.gson.GenerateTypeAdapter;

/**
 * This object represents the key of module extension in the lockfile represented by the extension
 * ID and the OS or architecture this extension depends on.
 */
@AutoValue
@GenerateTypeAdapter
public abstract class LockFileModuleExtensionKey implements Postable {

  public abstract ModuleExtensionId getExtensionId();

  public abstract String getOs();

  public abstract String getArch();

  public static LockFileModuleExtensionKey create(ModuleExtensionId extensionId, String os,
      String arch){
    return new AutoValue_LockFileModuleExtensionKey(extensionId, os, arch);
  }

}
