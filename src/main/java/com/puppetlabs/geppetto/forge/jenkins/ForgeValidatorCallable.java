/**
 * Copyright (c) 2014 Puppet Labs, Inc. and other contributors, as listed below.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Puppet Labs
 */
package com.puppetlabs.geppetto.forge.jenkins;

import hudson.remoting.VirtualChannel;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.emf.common.util.URI;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.puppetlabs.geppetto.common.os.FileUtils;
import com.puppetlabs.geppetto.common.os.StreamUtil.OpenBAStream;
import com.puppetlabs.geppetto.diagnostic.Diagnostic;
import com.puppetlabs.geppetto.diagnostic.ExceptionDiagnostic;
import com.puppetlabs.geppetto.diagnostic.FileDiagnostic;
import com.puppetlabs.geppetto.forge.model.ForgeDocs;
import com.puppetlabs.geppetto.forge.model.Metadata;
import com.puppetlabs.geppetto.forge.model.ModuleName;
import com.puppetlabs.geppetto.forge.model.NamedDocItem;
import com.puppetlabs.geppetto.forge.model.Type;
import com.puppetlabs.geppetto.forge.model.VersionedName;
import com.puppetlabs.geppetto.forge.util.ForgeStandaloneSetup;
import com.puppetlabs.geppetto.graph.DependencyGraphProducer;
import com.puppetlabs.geppetto.graph.ForgeValidationStandaloneSetup;
import com.puppetlabs.geppetto.graph.ProgressMonitorCancelIndicator;
import com.puppetlabs.geppetto.graph.SVGProducer;
import com.puppetlabs.geppetto.module.dsl.validation.IModuleValidationAdvisor;
import com.puppetlabs.geppetto.module.dsl.validation.ModuleValidationAdvisorBean;
import com.puppetlabs.geppetto.pp.dsl.validation.IPotentialProblemsAdvisor;
import com.puppetlabs.geppetto.pp.dsl.validation.IValidationAdvisor.ComplianceLevel;
import com.puppetlabs.geppetto.pp.dsl.validation.ValidationPreference;
import com.puppetlabs.geppetto.puppetlint.PuppetLintRunner;
import com.puppetlabs.geppetto.puppetlint.PuppetLintRunner.Issue;
import com.puppetlabs.geppetto.puppetlint.PuppetLintService;
import com.puppetlabs.geppetto.validation.FileType;
import com.puppetlabs.geppetto.validation.ValidationOptions;
import com.puppetlabs.geppetto.validation.ValidationService;
import com.puppetlabs.geppetto.validation.runner.BuildResult;
import com.puppetlabs.geppetto.validation.runner.IEncodingProvider;
import com.puppetlabs.geppetto.validation.runner.MetadataInfo;
import com.puppetlabs.graph.ICancel;

public class ForgeValidatorCallable extends ForgeCallable<ResultWithDiagnostic<byte[]>> {
	public static class UTF8EncodingProvider implements IEncodingProvider {
		private static final Charset UTF_8 = Charset.forName("UTF-8");

		@Override
		public String getEncoding(URI arg0) {
			return UTF_8.name();
		}
	}

	private static final long serialVersionUID = 1L;

	private static final Pattern GITHUB_REPO_URL_PATTERN = Pattern.compile("github.com[/:]([^/\\s]+)/([^/\\s]+)\\.git$");

	static final String IMPORTED_MODULES_ROOT = "importedModules";

	private String forgeServiceURL;

	private transient String sourceHrefPrefix;

	private boolean extractDocs;

	private boolean checkModuleSemantics;

	private boolean checkReferences;

	private boolean ignoreFileOverride;

	private ValidationPreference puppetLintMaxSeverity;

	private boolean puppetLintInverseOptions;

	private String[] puppetLintOptions;

	private IPotentialProblemsAdvisor problemsAdvisor;

	private boolean produceGraph;

	private IModuleValidationAdvisor moduleValidationAdvisor;

	private ComplianceLevel minComplianceLevel;

	private ComplianceLevel maxComplianceLevel;

	private Set<String> excludes;

	public ForgeValidatorCallable() {
	}

	public ForgeValidatorCallable(String forgeServiceURL, String sourceURI, boolean ignoreFileOverride, Set<String> excludes,
			String branchName, ComplianceLevel minComplianceLevel, ComplianceLevel maxComplianceLevel, boolean checkReferences,
			boolean checkModuleSemantics, IPotentialProblemsAdvisor problemsAdvisor, IModuleValidationAdvisor moduleValidationAdvisor,
			ValidationPreference puppetLintMaxSeverity, boolean puppetLintInverseOptions, String[] puppetLintOptions, boolean extractDocs,
			boolean produceGraph) {
		super(sourceURI, branchName);
		this.forgeServiceURL = forgeServiceURL;
		this.excludes = excludes;
		this.minComplianceLevel = minComplianceLevel;
		this.maxComplianceLevel = maxComplianceLevel;
		this.checkReferences = checkReferences;
		this.checkModuleSemantics = checkModuleSemantics;
		this.ignoreFileOverride = ignoreFileOverride;
		this.problemsAdvisor = problemsAdvisor;
		if(!(checkModuleSemantics && checkReferences) && moduleValidationAdvisor.getUnresolvedReference() != ValidationPreference.IGNORE) {
			if(!(moduleValidationAdvisor instanceof ModuleValidationAdvisorBean))
				moduleValidationAdvisor = new ModuleValidationAdvisorBean(moduleValidationAdvisor);
			((ModuleValidationAdvisorBean) moduleValidationAdvisor).setUnresolvedReference(ValidationPreference.IGNORE);
		}

		this.moduleValidationAdvisor = moduleValidationAdvisor;
		this.puppetLintMaxSeverity = puppetLintMaxSeverity;
		this.puppetLintInverseOptions = puppetLintInverseOptions;
		this.puppetLintOptions = puppetLintOptions;
		this.extractDocs = extractDocs;
		this.produceGraph = produceGraph;
	}

	private void addGeppettoResult(Diagnostic geppettoDiag, byte[] svg, ResultWithDiagnostic<byte[]> result) {
		result.setResult(svg);
		convertChildren(geppettoDiag);
		result.addChildren(geppettoDiag.getChildren());
	}

	private void convertChildren(Diagnostic diag) {
		List<Diagnostic> children = diag.getChildren();
		int idx = children.size();
		if(idx == 0)
			return;

		boolean replace = false;
		List<Diagnostic> newChildren = Lists.newArrayList(children);
		while(--idx >= 0) {
			Diagnostic child = children.get(idx);
			if(child instanceof FileDiagnostic) {
				replace = true;
				newChildren.set(idx, convertFileDiagnostic((FileDiagnostic) child));
			}
			else
				convertChildren(child);
		}
		if(replace)
			diag.setChildren(newChildren);
	}

	private Diagnostic convertFileDiagnostic(FileDiagnostic fd) {
		return new ValidationDiagnostic(fd, getSourceHrefPrefix(), getRelativePath(fd.getFile()));
	}

	private Diagnostic convertPuppetLintDiagnostic(File moduleRoot, PathMatcher matcher, Issue issue) {
		if(matcher.matches(Paths.get(".", issue.getPath())))
			return null;
		return new ValidationDiagnostic(
			getSeverity(issue), PuppetLintService.PUPPET_LINT, issue.getMessage(), getSourceHrefPrefix(), getRelativePath(new File(
				moduleRoot, issue.getPath())), issue.getLineNumber());
	}

	@Override
	protected ForgeStandaloneSetup createForgeBindings() {
		return new ForgeValidationStandaloneSetup(forgeServiceURL, getSourceHrefPrefix());
	}

	private ValidationOptions geppettoValidation(Collection<File> moduleLocations, ResultWithDiagnostic<byte[]> result) throws IOException {

		Diagnostic geppettoDiag = new Diagnostic();
		Collection<File> importedModuleLocations = null;
		List<Metadata> metadatas = new ArrayList<Metadata>();
		for(File moduleRoot : moduleLocations) {
			Metadata md = getModuleMetadata(moduleRoot, geppettoDiag);
			if(md != null)
				metadatas.add(md);
		}

		if(metadatas.isEmpty()) {
			addGeppettoResult(geppettoDiag, null, result);
			return null;
		}

		if(metadatas.size() == 1) {
			Metadata md = metadatas.get(0);
			result.addChild(new Diagnostic(Diagnostic.INFO, ValidationService.MODULE, String.format(
				"%s%s-%s", ValidationResult.RELEASE_PREFIX, md.getName(), md.getVersion())));
		}

		if(checkReferences) {
			File importedModulesDir = new File(getBuildDir(), IMPORTED_MODULES_ROOT);
			importedModuleLocations = getForgeBindings().getForgeService(geppettoDiag).downloadDependencies(
				metadatas, importedModulesDir, geppettoDiag);
		}
		if(importedModuleLocations == null)
			importedModuleLocations = Collections.emptyList();

		// Any diagnostic that the metadata retrieval gave will be added again when we validate
		geppettoDiag = new Diagnostic();
		ValidationOptions bestOptions = null;
		ComplianceDiagnostic bestDiag = null;
		BuildResult bestResult = null;

		Diagnostic levelDiags = new Diagnostic();
		int maxErrors = Integer.MAX_VALUE;
		int maxWarnings = Integer.MAX_VALUE;
		for(ComplianceLevel level : ComplianceLevel.values()) {
			if(level.ordinal() < minComplianceLevel.ordinal())
				continue;
			if(level.ordinal() > maxComplianceLevel.ordinal())
				break;

			ComplianceDiagnostic levelDiag = new ComplianceDiagnostic(level);
			levelDiag.setMessage(level.toString());
			ValidationOptions options = getValidationOptions(level, moduleLocations, importedModuleLocations, levelDiag);
			BuildResult buildResult = getValidationService(levelDiag).validate(
				levelDiag, options, getSourceDir(), new NullProgressMonitor());

			int errors = 0;
			int warnings = 0;
			for(Diagnostic d : levelDiag) {
				switch(d.getSeverity()) {
					case Diagnostic.ERROR:
						++errors;
						break;
					case Diagnostic.WARNING:
						++warnings;
				}
			}

			if(bestResult == null || (errors < maxErrors || errors == maxErrors && warnings <= maxWarnings)) {
				bestDiag = levelDiag;
				bestOptions = options;
				bestResult = buildResult;
				maxErrors = errors;
				maxWarnings = warnings;
			}
			levelDiags.addChild(levelDiag);
		}
		MultiComplianceDiagnostic mcDiags = new MultiComplianceDiagnostic(bestDiag.getComplianceLevel());
		mcDiags.addChildren(levelDiags.getChildren());
		geppettoDiag.addChild(mcDiags);

		byte[] svg = null;
		if(bestOptions.isProduceGraph() && checkModuleSemantics && geppettoDiag.getSeverity() < Diagnostic.ERROR) {
			OpenBAStream dotStream = new OpenBAStream();
			ICancel cancel = new ProgressMonitorCancelIndicator(new NullProgressMonitor(), 1);
			getGraphProducer(geppettoDiag).produceGraph(
				cancel, "", moduleLocations.toArray(new File[moduleLocations.size()]), dotStream, bestResult, result);
			svg = produceSVG(dotStream.getInputStream(), geppettoDiag);
		}

		addGeppettoResult(geppettoDiag, svg, result);
		if(extractDocs) {
			Map<VersionedName, ForgeDocs> extractedDocs = Maps.newHashMap();
			Multimap<ModuleName, MetadataInfo> moduleData = bestResult.getModuleData();
			if(moduleData != null)
				for(MetadataInfo mi : bestResult.getModuleData().values()) {
					Collection<Type> types = mi.getTypes();
					Collection<NamedDocItem> functions = mi.getFunctions();
					if(types.size() > 0 || functions.size() > 0) {
						Metadata md = mi.getMetadata();
						VersionedName release = new VersionedName(md.getName(), md.getVersion());
						ForgeDocs fd = new ForgeDocs();
						fd.setRelease(release);
						fd.setFunctions(functions);
						fd.setTypes(types);
						extractedDocs.put(release, fd);
					}
				}
			result.setExtractedDocs(extractedDocs);
		}
		return bestOptions;
	}

	public DependencyGraphProducer getGraphProducer(Diagnostic diag) {
		return getForgeBindings().getInstance(DependencyGraphProducer.class, diag);
	}

	private String getRelativePath(File file) {
		IPath rootPath = Path.fromOSString(getSourceDir().getAbsolutePath());
		IPath relative;
		if(file.isAbsolute()) {
			IPath path = Path.fromOSString(file.getAbsolutePath());
			relative = path.makeRelativeTo(rootPath);
		}
		else
			relative = Path.fromOSString(file.getPath());
		return relative.toPortableString();
	}

	private String getSearchPath(Collection<File> moduleLocations, Collection<File> importedModuleLocations) {
		StringBuilder searchPath = new StringBuilder();

		searchPath.append("lib/*:environments/$environment/*");

		for(File moduleLocation : moduleLocations)
			searchPath.append(":" + getRelativePath(moduleLocation) + "/*");

		for(File importedModuleLocation : importedModuleLocations)
			searchPath.append(":" + importedModuleLocation + "/*");
		return searchPath.toString();
	}

	private int getSeverity(Issue issue) {
		switch(issue.getSeverity()) {
			case ERROR:
				return puppetLintMaxSeverity == ValidationPreference.WARNING
					? Diagnostic.WARNING
					: Diagnostic.ERROR;
			default:
				return Diagnostic.WARNING;
		}
	}

	private synchronized String getSourceHrefPrefix() {
		if(sourceHrefPrefix == null) {
			String sourceURI = getSourceURI();
			if(sourceURI != null) {
				Matcher m = GITHUB_REPO_URL_PATTERN.matcher(sourceURI);
				if(m.find()) {
					sourceHrefPrefix = String.format("https://github.com/%s/%s/blob/%s/", m.group(1), m.group(2), getBranchName());
				}
				else
					sourceHrefPrefix = sourceURI;
			}
			else
				sourceHrefPrefix = "";
		}
		return sourceHrefPrefix.length() == 0
			? null
			: sourceHrefPrefix;
	}

	public SVGProducer getSVGProducer(Diagnostic diag) {
		return getForgeBindings().getInstance(SVGProducer.class, diag);
	}

	private ValidationOptions getValidationOptions(ComplianceLevel complianceLevel, Collection<File> moduleLocations,
			Collection<File> importedModuleLocations, Diagnostic diag) {
		ValidationOptions options = new ValidationOptions();
		options.setCheckLayout(true);
		options.setCheckModuleSemantics(checkModuleSemantics);
		options.setCheckReferences(checkReferences);
		options.setExtractDocs(extractDocs);
		options.setValidationRoot(getSourceDir());

		if(moduleLocations.size() == 1 && getSourceDir().equals(moduleLocations.iterator().next()))
			options.setFileType(FileType.MODULE_ROOT);
		else
			options.setFileType(FileType.PUPPET_ROOT);

		options.setComplianceLevel(complianceLevel);
		options.setEncodingProvider(new UTF8EncodingProvider());
		options.setValidationFilter(new FileFilter() {
			@Override
			public boolean accept(File file) {
				return !isParentOrEqual(getBuildDir(), file);
			}
		});
		options.setSearchPath(getSearchPath(moduleLocations, importedModuleLocations));
		options.setProblemsAdvisor(problemsAdvisor);
		options.setModuleValidationAdvisor(moduleValidationAdvisor);
		options.setExcludeGlobs(excludes);
		options.setAllowFileOverride(!ignoreFileOverride);
		options.setProduceGraph(produceGraph);
		if(options.isAllowFileOverride())
			options = options.mergeFileOverride(diag);

		return options;
	}

	ValidationService getValidationService(Diagnostic diag) {
		return getForgeBindings().getInstance(ValidationService.class, diag);
	}

	@Override
	public ResultWithDiagnostic<byte[]> invoke(VirtualChannel channel) throws IOException, InterruptedException {
		ResultWithDiagnostic<byte[]> result = new ResultWithDiagnostic<byte[]>();
		FileUtils.rmR(getBuildDir());
		Collection<File> moduleRoots = findModuleRoots(result);
		if(moduleRoots.isEmpty()) {
			result.addChild(new Diagnostic(Diagnostic.ERROR, ValidationService.MODULE, "No modules found in repository"));
			return result;
		}

		ValidationOptions options = geppettoValidation(moduleRoots, result);
		if(options != null && puppetLintMaxSeverity != ValidationPreference.IGNORE)
			lintValidation(moduleRoots, options, result);
		return result;
	}

	private void lintValidation(Collection<File> moduleLocations, ValidationOptions options, Diagnostic result) {
		PuppetLintRunner runner = PuppetLintService.getInstance().getPuppetLintRunner();
		try {
			PathMatcher matcher = FileUtils.createGlobMatcher(options.getExcludeGlobs());
			for(File moduleRoot : moduleLocations) {
				for(PuppetLintRunner.Issue issue : runner.run(moduleRoot, puppetLintInverseOptions, puppetLintOptions)) {
					Diagnostic diag = convertPuppetLintDiagnostic(moduleRoot, matcher, issue);
					if(diag != null)
						result.addChild(diag);
				}
			}
		}
		catch(IOException e) {
			result.addChild(new ExceptionDiagnostic(Diagnostic.ERROR, PuppetLintService.PUPPET_LINT, e.getMessage(), e));
		}
	}

	private byte[] produceSVG(InputStream dotStream, Diagnostic diag) throws IOException {
		ByteArrayOutputStream svgStream = new ByteArrayOutputStream();
		getSVGProducer(diag).produceSVG(dotStream, svgStream, false, new NullProgressMonitor());
		return svgStream.toByteArray();
	}
}
