// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.cpp;

import static com.google.devtools.build.lib.packages.Type.BOOLEAN;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Actions;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.Allowlist;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.LicensesProvider;
import com.google.devtools.build.lib.analysis.LicensesProvider.TargetLicense;
import com.google.devtools.build.lib.analysis.LicensesProviderImpl;
import com.google.devtools.build.lib.analysis.PackageSpecificationProvider;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.BuiltinProvider;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.License;
import com.google.devtools.build.lib.packages.NativeInfo;
import com.google.devtools.build.lib.packages.Type;
import javax.annotation.Nullable;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.StarlarkFunction;
import net.starlark.java.eval.StarlarkThread;

/**
 * Provider encapsulating all the information from the cc_toolchain rule that affects creation of
 * {@link CcToolchainProvider}
 */
// TODO(adonovan): rename s/Provider/Info/.
public class CcToolchainAttributesProvider extends NativeInfo implements HasCcToolchainLabel {

  public static final BuiltinProvider<CcToolchainAttributesProvider> PROVIDER =
      new BuiltinProvider<CcToolchainAttributesProvider>(
          "CcToolchainAttributesInfo", CcToolchainAttributesProvider.class) {};

  private final boolean supportsParamFiles;
  private final boolean supportsHeaderParsing;
  private final NestedSet<Artifact> allFiles;
  private final NestedSet<Artifact> compilerFiles;
  private final NestedSet<Artifact> compilerFilesWithoutIncludes;
  private final NestedSet<Artifact> stripFiles;
  private final NestedSet<Artifact> objcopyFiles;
  private final NestedSet<Artifact> asFiles;
  private final NestedSet<Artifact> arFiles;
  private final NestedSet<Artifact> linkerFiles;
  private final NestedSet<Artifact> dwpFiles;
  private final NestedSet<Artifact> libc;
  private final TransitiveInfoCollection libcTop;
  private final NestedSet<Artifact> targetLibc;
  private final TransitiveInfoCollection targetLibcTop;
  private final NestedSet<Artifact> fullInputsForCrosstool;
  private final NestedSet<Artifact> fullInputsForLink;
  private final NestedSet<Artifact> coverage;
  private final String compiler;
  private final String cpu;
  private final Artifact ifsoBuilder;
  private final Artifact linkDynamicLibraryTool;
  @Nullable private final Artifact grepIncludes;
  private final TransitiveInfoCollection fdoOptimize;
  private final ImmutableList<Artifact> fdoOptimizeArtifacts;
  private final FdoPrefetchHintsProvider fdoPrefetch;
  private final PropellerOptimizeProvider propellerOptimize;
  private final MemProfProfileProvider memprofProfileProvider;
  private final TransitiveInfoCollection moduleMap;
  private final Artifact moduleMapArtifact;
  private final Artifact zipper;
  private final Artifact defaultZipper;
  private final String purposePrefix;
  private final String runtimeSolibDirBase;
  private final LicensesProvider licensesProvider;
  private final Label toolchainType;
  private final CcToolchainConfigInfo ccToolchainConfigInfo;
  private final String toolchainIdentifier;
  private final FdoProfileProvider fdoOptimizeProvider;
  private final FdoProfileProvider fdoProfileProvider;
  private final FdoProfileProvider csFdoProfileProvider;
  private final FdoProfileProvider xfdoProfileProvider;
  private final Label ccToolchainLabel;
  private final TransitiveInfoCollection staticRuntimeLib;
  private final TransitiveInfoCollection dynamicRuntimeLib;
  private final PackageSpecificationProvider allowlistForLayeringCheck;
  private final PackageSpecificationProvider allowlistForLooseHeaderCheck;
  private final StarlarkFunction ccToolchainBuildVariablesFunc;
  private final String lateBoundLibc;
  private final String lateBoundTargetLibc;

  public CcToolchainAttributesProvider(
      RuleContext ruleContext,
      boolean isAppleToolchain,
      StarlarkFunction ccToolchainBuildVariablesFunc) {
    super();
    this.ccToolchainLabel = ruleContext.getLabel();
    this.toolchainIdentifier = ruleContext.attributes().get("toolchain_identifier", Type.STRING);
    if (ruleContext.getFragment(CppConfiguration.class).removeCpuCompilerCcToolchainAttributes()
        && (ruleContext.attributes().isAttributeValueExplicitlySpecified("cpu")
            || ruleContext.attributes().isAttributeValueExplicitlySpecified("compiler"))) {
      ruleContext.ruleError(
          "attributes 'cpu' and 'compiler' have been deprecated, please remove them. See "
              + "https://github.com/bazelbuild/bazel/issues/7075 for details.");
    }

    // grep_includes is not supported by Bazel.
    String toolsRepository = ruleContext.getRuleClassProvider().getToolsRepository().getName();
    this.grepIncludes =
        toolsRepository.isEmpty() ? ruleContext.getPrerequisiteArtifact("$grep_includes") : null;

    this.cpu = ruleContext.attributes().get("cpu", Type.STRING);
    this.compiler = ruleContext.attributes().get("compiler", Type.STRING);
    this.supportsParamFiles = ruleContext.attributes().get("supports_param_files", BOOLEAN);
    this.supportsHeaderParsing = ruleContext.attributes().get("supports_header_parsing", BOOLEAN);
    this.allFiles = getFiles(ruleContext, "all_files");
    this.compilerFiles = getFiles(ruleContext, "compiler_files");
    this.compilerFilesWithoutIncludes =
        getOptionalFiles(ruleContext, "compiler_files_without_includes");
    this.stripFiles = getFiles(ruleContext, "strip_files");
    this.objcopyFiles = getFiles(ruleContext, "objcopy_files");
    this.asFiles = getOptionalFiles(ruleContext, "as_files");
    this.arFiles = getOptionalFiles(ruleContext, "ar_files");
    this.linkerFiles = getFiles(ruleContext, "linker_files");
    this.dwpFiles = getFiles(ruleContext, "dwp_files");

    this.lateBoundLibc = getLateBoundLibc(ruleContext, "libc_top", ":libc_top");
    this.lateBoundTargetLibc = getLateBoundLibc(ruleContext, "libc_top", ":target_libc_top");

    this.libc = getOptionalFiles(ruleContext, lateBoundLibc);
    this.libcTop = ruleContext.getPrerequisite(lateBoundLibc);

    this.targetLibc = getOptionalFiles(ruleContext, lateBoundTargetLibc);
    this.targetLibcTop = ruleContext.getPrerequisite(lateBoundTargetLibc);

    this.fullInputsForCrosstool =
        NestedSetBuilder.<Artifact>stableOrder()
            .addTransitive(allFiles)
            .addTransitive(libc)
            .build();
    this.fullInputsForLink = fullInputsForLink(ruleContext, linkerFiles, libc, isAppleToolchain);
    NestedSet<Artifact> coverageFiles = getOptionalFiles(ruleContext, "coverage_files");
    if (coverageFiles.isEmpty()) {
      this.coverage = Preconditions.checkNotNull(this.allFiles);
    } else {
      this.coverage = coverageFiles;
    }
    this.ifsoBuilder = ruleContext.getPrerequisiteArtifact("$interface_library_builder");
    this.linkDynamicLibraryTool = ruleContext.getPrerequisiteArtifact("$link_dynamic_library_tool");
    this.fdoProfileProvider =
        ruleContext.getPrerequisite(CcToolchainRule.FDO_PROFILE_ATTR, FdoProfileProvider.PROVIDER);
    this.csFdoProfileProvider =
        ruleContext.getPrerequisite(
            CcToolchainRule.CSFDO_PROFILE_ATTR, FdoProfileProvider.PROVIDER);
    this.xfdoProfileProvider =
        ruleContext.getPrerequisite(CcToolchainRule.XFDO_PROFILE_ATTR, FdoProfileProvider.PROVIDER);
    this.fdoOptimizeProvider =
        ruleContext.getPrerequisite(CcToolchainRule.FDO_OPTIMIZE_ATTR, FdoProfileProvider.PROVIDER);
    this.fdoOptimize = ruleContext.getPrerequisite(CcToolchainRule.FDO_OPTIMIZE_ATTR);
    this.fdoOptimizeArtifacts =
        ruleContext.getPrerequisiteArtifacts(CcToolchainRule.FDO_OPTIMIZE_ATTR).list();
    this.fdoPrefetch =
        ruleContext.getPrerequisite(":fdo_prefetch_hints", FdoPrefetchHintsProvider.PROVIDER);
    this.propellerOptimize =
        ruleContext.getPrerequisite(":propeller_optimize", PropellerOptimizeProvider.PROVIDER);
    this.memprofProfileProvider =
        ruleContext.getPrerequisite(":memprof_profile", MemProfProfileProvider.PROVIDER);
    this.moduleMap = ruleContext.getPrerequisite("module_map");
    this.moduleMapArtifact = ruleContext.getPrerequisiteArtifact("module_map");
    this.zipper = ruleContext.getPrerequisiteArtifact(":zipper");
    this.defaultZipper = ruleContext.getPrerequisiteArtifact(":default_zipper");
    this.purposePrefix = Actions.escapeLabel(ruleContext.getLabel()) + "_";
    this.runtimeSolibDirBase = "_solib_" + "_" + Actions.escapeLabel(ruleContext.getLabel());
    this.staticRuntimeLib = ruleContext.getPrerequisite("static_runtime_lib");
    this.dynamicRuntimeLib = ruleContext.getPrerequisite("dynamic_runtime_lib");
    this.ccToolchainConfigInfo =
        ruleContext.getPrerequisite(
            CcToolchainRule.TOOLCHAIN_CONFIG_ATTR,
            CcToolchainConfigInfo.PROVIDER);

    // If output_license is specified on the cc_toolchain rule, override the transitive licenses
    // with that one. This is necessary because cc_toolchain is used in the target configuration,
    // but it is sort-of-kind-of a tool, but various parts of it are linked into the output...
    // ...so we trust the judgment of the author of the cc_toolchain rule to figure out what
    // licenses should be propagated to C++ targets.
    // TODO(elenairina): Remove this and use Attribute.Builder.useOutputLicenses() on the
    // :cc_toolchain attribute instead.
    final License outputLicense =
        ruleContext.getRule().getToolOutputLicense(ruleContext.attributes());
    if (outputLicense != null && !outputLicense.equals(License.NO_LICENSE)) {
      final NestedSet<TargetLicense> license =
          NestedSetBuilder.create(
              Order.STABLE_ORDER, new TargetLicense(ruleContext.getLabel(), outputLicense));
      this.licensesProvider =
          new LicensesProviderImpl(
              license, new TargetLicense(ruleContext.getLabel(), outputLicense));
    } else {
      this.licensesProvider = null;
    }
    // TODO(b/65835260): Remove this conditional once j2objc can learn the toolchain type.
    if (ruleContext.attributes().has(CcToolchainRule.CC_TOOLCHAIN_TYPE_ATTRIBUTE_NAME)) {
      this.toolchainType =
          ruleContext
              .attributes()
              .get(CcToolchainRule.CC_TOOLCHAIN_TYPE_ATTRIBUTE_NAME, BuildType.LABEL);
    } else {
      this.toolchainType = null;
    }
    this.allowlistForLayeringCheck =
        Allowlist.fetchPackageSpecificationProvider(
            ruleContext, CcToolchainRule.ALLOWED_LAYERING_CHECK_FEATURES_ALLOWLIST);
    this.allowlistForLooseHeaderCheck =
        Allowlist.fetchPackageSpecificationProvider(
            ruleContext, CcToolchainRule.LOOSE_HEADER_CHECK_ALLOWLIST);
    this.ccToolchainBuildVariablesFunc = ccToolchainBuildVariablesFunc;
  }

  // This is to avoid Starlark limitation of not being able to have complex logic in configuration
  // field. The logic here was encapsulated in native cc_toolchain rule's :libc_top's and
  // :target_libc_top's LateBoundDefault attributes.
  // In case :libc_top or :target_libc_top were not specified from command line, i.e. grte_top was
  // not set we will try to use public attributes instead.
  private static String getLateBoundLibc(
      RuleContext ruleContext, String attribute, String implicitAttribute) {
    if (ruleContext.getPrerequisite(implicitAttribute) == null) {
      return attribute;
    }
    return implicitAttribute;
  }

  @Override
  public BuiltinProvider<CcToolchainAttributesProvider> getProvider() {
    return PROVIDER;
  }

  @StarlarkMethod(
      name = "build_vars_func",
      documented = false,
      useStarlarkThread = true,
      allowReturnNones = true)
  @Nullable
  public StarlarkFunction getBuildVarsFunc(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return ccToolchainBuildVariablesFunc;
  }

  public String getCpu() {
    return cpu;
  }

  public boolean isSupportsParamFiles() {
    return supportsParamFiles;
  }

  public String getPurposePrefix() {
    return purposePrefix;
  }

  @StarlarkMethod(name = "runtime_solib_dir_base", documented = false, useStarlarkThread = true)
  public String getRuntimeSolibDirBaseForStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return getRuntimeSolibDirBase();
  }

  public String getRuntimeSolibDirBase() {
    return runtimeSolibDirBase;
  }

  public StarlarkFunction getCcToolchainBuildVariablesFunc() {
    return ccToolchainBuildVariablesFunc;
  }

  public FdoPrefetchHintsProvider getFdoPrefetch() {
    return fdoPrefetch;
  }

  public PropellerOptimizeProvider getPropellerOptimize() {
    return propellerOptimize;
  }

  public MemProfProfileProvider getMemProfProfileProvider() {
    return memprofProfileProvider;
  }

  public String getToolchainIdentifier() {
    return toolchainIdentifier;
  }

  public Label getToolchainType() {
    return toolchainType;
  }

  @StarlarkMethod(name = "cc_toolchain_config_info", documented = false, useStarlarkThread = true)
  public CcToolchainConfigInfo getCcToolchainConfigInfoForStarlark(StarlarkThread thread)
      throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return getCcToolchainConfigInfo();
  }

  public CcToolchainConfigInfo getCcToolchainConfigInfo() {
    return ccToolchainConfigInfo;
  }

  public ImmutableList<Artifact> getFdoOptimizeArtifacts() {
    return fdoOptimizeArtifacts;
  }

  @StarlarkMethod(
      name = "licenses_provider",
      documented = false,
      useStarlarkThread = true,
      allowReturnNones = true)
  @Nullable
  public LicensesProvider getLicensesProviderForStarlark(StarlarkThread thread)
      throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return getLicensesProvider();
  }

  public LicensesProvider getLicensesProvider() {
    return licensesProvider;
  }

  @StarlarkMethod(
      name = "static_runtime_lib",
      documented = false,
      useStarlarkThread = true,
      allowReturnNones = true)
  @Nullable
  public TransitiveInfoCollection getStaticRuntimeLibForStarlark(StarlarkThread thread)
      throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return getStaticRuntimeLib();
  }

  public TransitiveInfoCollection getStaticRuntimeLib() {
    return staticRuntimeLib;
  }

  @StarlarkMethod(
      name = "dynamic_runtime_lib",
      documented = false,
      useStarlarkThread = true,
      allowReturnNones = true)
  @Nullable
  public TransitiveInfoCollection getDynamicRuntimeLibForStarlark(StarlarkThread thread)
      throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return getDynamicRuntimeLib();
  }

  public TransitiveInfoCollection getDynamicRuntimeLib() {
    return dynamicRuntimeLib;
  }

  public boolean isSupportsHeaderParsing() {
    return supportsHeaderParsing;
  }

  public NestedSet<Artifact> getAllFiles() {
    return allFiles;
  }

  public NestedSet<Artifact> getCompilerFiles() {
    return compilerFiles;
  }

  public NestedSet<Artifact> getStripFiles() {
    return stripFiles;
  }

  public NestedSet<Artifact> getObjcopyFiles() {
    return objcopyFiles;
  }

  public TransitiveInfoCollection getFdoOptimize() {
    return fdoOptimize;
  }

  public Artifact getLinkDynamicLibraryTool() {
    return linkDynamicLibraryTool;
  }

  @Nullable
  public Artifact getGrepIncludes() {
    return grepIncludes;
  }

  @StarlarkMethod(
      name = "module_map",
      documented = false,
      useStarlarkThread = true,
      allowReturnNones = true)
  @Nullable
  public TransitiveInfoCollection getModuleMapForStarlark(StarlarkThread thread)
      throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return getModuleMap();
  }

  public TransitiveInfoCollection getModuleMap() {
    return moduleMap;
  }

  public NestedSet<Artifact> getAsFiles() {
    return asFiles;
  }

  public NestedSet<Artifact> getArFiles() {
    return arFiles;
  }

  public TransitiveInfoCollection getLibcTop() {
    return libcTop;
  }

  public NestedSet<Artifact> getLinkerFiles() {
    return linkerFiles;
  }

  public NestedSet<Artifact> getDwpFiles() {
    return dwpFiles;
  }

  public FdoProfileProvider getFdoOptimizeProvider() {
    return fdoOptimizeProvider;
  }

  @StarlarkMethod(name = "module_map_artifact", documented = false, useStarlarkThread = true)
  public Artifact getModuleMapArtifactForStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return getModuleMapArtifact();
  }

  public Artifact getModuleMapArtifact() {
    return moduleMapArtifact;
  }

  public NestedSet<Artifact> getFullInputsForCrosstool() {
    return fullInputsForCrosstool;
  }

  public FdoProfileProvider getFdoProfileProvider() {
    return fdoProfileProvider;
  }

  public FdoProfileProvider getCSFdoProfileProvider() {
    return csFdoProfileProvider;
  }

  public FdoProfileProvider getXFdoProfileProvider() {
    return xfdoProfileProvider;
  }

  /* Get the FDO-specific zipper. */
  public Artifact getZipper() {
    return zipper;
  }

  /* Get the non FDO-specific zipper. */
  public Artifact getDefaultZipper() {
    return defaultZipper;
  }

  public NestedSet<Artifact> getFullInputsForLink() {
    return fullInputsForLink;
  }

  @StarlarkMethod(name = "cc_toolchain_label", documented = false, useStarlarkThread = true)
  public Label getCcToolchainLabelForStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return getCcToolchainLabel();
  }

  @Override
  public Label getCcToolchainLabel() {
    return ccToolchainLabel;
  }

  public NestedSet<Artifact> getCoverage() {
    return coverage;
  }

  public NestedSet<Artifact> getCompilerFilesWithoutIncludes() {
    return compilerFilesWithoutIncludes;
  }

  @StarlarkMethod(name = "libc", documented = false, useStarlarkThread = true)
  public Depset getLibcForStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return Depset.of(Artifact.class, getLibc());
  }

  @StarlarkMethod(name = "target_libc", documented = false, useStarlarkThread = true)
  public Depset getTargetLibcForstarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return Depset.of(Artifact.class, getTargetLibc());
  }

  public NestedSet<Artifact> getLibc() {
    return libc;
  }

  public NestedSet<Artifact> getTargetLibc() {
    return targetLibc;
  }

  public TransitiveInfoCollection getTargetLibcTop() {
    return targetLibcTop;
  }

  @StarlarkMethod(
      name = "libc_top_label",
      documented = false,
      allowReturnNones = true,
      useStarlarkThread = true)
  @Nullable
  public Label getLibcTopLabelForStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return getLibcTopLabel();
  }

  @Nullable
  public Label getLibcTopLabel() {
    return getLibcTop() == null ? null : getLibcTop().getLabel();
  }

  @StarlarkMethod(
      name = "target_libc_top_label",
      documented = false,
      allowReturnNones = true,
      useStarlarkThread = true)
  @Nullable
  public Label getTargetLibcTopLabelForStarlark(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return getTargetLibcTopLabel();
  }

  @Nullable
  public Label getTargetLibcTopLabel() {
    return getTargetLibcTop() == null ? null : getTargetLibcTop().getLabel();
  }

  public String getCompiler() {
    return compiler;
  }

  public Artifact getIfsoBuilder() {
    return ifsoBuilder;
  }

  public PackageSpecificationProvider getAllowlistForLayeringCheck() {
    return allowlistForLayeringCheck;
  }

  public PackageSpecificationProvider getAllowlistForLooseHeaderCheck() {
    return allowlistForLooseHeaderCheck;
  }

  private static NestedSet<Artifact> getFiles(RuleContext context, String attribute) {
    TransitiveInfoCollection dep = context.getPrerequisite(attribute);
    return dep.getProvider(FileProvider.class).getFilesToBuild();
  }

  private static NestedSet<Artifact> getOptionalFiles(RuleContext ruleContext, String attribute) {
    TransitiveInfoCollection dep = ruleContext.getPrerequisite(attribute);
    return dep != null
        ? dep.getProvider(FileProvider.class).getFilesToBuild()
        : NestedSetBuilder.emptySet(Order.STABLE_ORDER);
  }

  /**
   * Returns the allFiles-derived link action inputs for a given rule. Adds the given set of
   * artifacts as extra inputs.
   */
  private static NestedSet<Artifact> fullInputsForLink(
      RuleContext ruleContext,
      NestedSet<Artifact> link,
      NestedSet<Artifact> libcMiddleman,
      boolean isAppleToolchain) {
    NestedSetBuilder<Artifact> builder =
        NestedSetBuilder.<Artifact>stableOrder().addTransitive(link).addTransitive(libcMiddleman);
    if (!isAppleToolchain) {
      builder
          .add(ruleContext.getPrerequisiteArtifact("$interface_library_builder"))
          .add(ruleContext.getPrerequisiteArtifact("$link_dynamic_library_tool"));
    }
    return builder.build();
  }
}

/**
 * Temporary interface to cover common interface of {@link CcToolchainAttributesProvider} and {@link
 * CcToolchainProvider}.
 */
// TODO(b/113849758): Remove once behavior is migrated.
interface HasCcToolchainLabel extends Info {
  Label getCcToolchainLabel();
}
