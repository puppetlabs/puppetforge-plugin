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

import static com.puppetlabs.geppetto.injectable.CommonModuleProvider.getCommonModule;
import hudson.FilePath.FileCallable;
import hudson.remoting.VirtualChannel;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.google.inject.Injector;
import com.google.inject.Module;
import com.puppetlabs.geppetto.diagnostic.Diagnostic;
import com.puppetlabs.geppetto.forge.Forge;
import com.puppetlabs.geppetto.forge.impl.ForgeModule;
import com.puppetlabs.geppetto.forge.model.Metadata;
// import org.jenkinsci.remoting.RoleChecker;
import com.google.inject.Guice;

public abstract class ForgeCallable<T> implements FileCallable<T> {
	private static final long serialVersionUID = -3048930993120683688L;

	public static final String BUILD_DIR = ".geppetto";

	public static final String IMPORTED_MODULES_ROOT = "importedModules";

	public static boolean isParentOrEqual(File dir, File subdir) {
		if(dir == null || subdir == null)
			return false;

		return dir.equals(subdir) || isParentOrEqual(dir, subdir.getParentFile());
	}

	private transient File buildDir;

	private transient Injector injector;

	private transient File sourceDir;

	private String sourceURI;

	private String branchName;

	public ForgeCallable() {
	}

	public ForgeCallable(String sourceURI, String branchName) {
		this.sourceURI = sourceURI;
		this.branchName = branchName;
	}

	protected void addModules(Diagnostic diagnostic, List<Module> modules) {
		modules.add(new ForgeModule());
		modules.add(getCommonModule());
	}

	//	@Override
	//	public void checkRoles(RoleChecker checker) throws SecurityException {
	//	}
	//
	protected Injector createInjector(List<Module> modules) {
		return Guice.createInjector(modules);
	}

	protected Collection<File> findModuleRoots(Diagnostic diag) {
		return getForge(diag).findModuleRoots(getSourceDir(), null);
	}

	public String getBranchName() {
		return branchName;
	}

	protected File getBuildDir() {
		return buildDir;
	}

	protected Forge getForge(Diagnostic diag) {
		return getInjector(diag).getInstance(Forge.class);
	}

	synchronized Injector getInjector(Diagnostic diag) {
		if(injector == null) {
			List<Module> modules = new ArrayList<Module>();
			addModules(diag, modules);
			if(diag.getSeverity() <= Diagnostic.WARNING) {
				injector = createInjector(modules);
			}
		}
		return injector;
	}

	protected Metadata getModuleMetadata(File moduleDirectory, Diagnostic diag) throws IOException {
		return getForge(diag).createFromModuleDirectory(moduleDirectory, null, null, diag);
	}

	protected File getSourceDir() {
		return sourceDir;
	}

	public String getSourceURI() {
		return sourceURI;
	}

	@Override
	public final T invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
		sourceDir = f;
		buildDir = new File(f, BUILD_DIR);
		return invoke(channel);
	}

	protected abstract T invoke(VirtualChannel channel) throws IOException, InterruptedException;
}
