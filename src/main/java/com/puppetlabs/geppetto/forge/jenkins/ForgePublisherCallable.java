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

import static com.puppetlabs.geppetto.diagnostic.Diagnostic.ERROR;
import static com.puppetlabs.geppetto.forge.Forge.FORGE;
import hudson.remoting.VirtualChannel;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.google.inject.Module;
import com.puppetlabs.geppetto.diagnostic.Diagnostic;
import com.puppetlabs.geppetto.forge.Forge;
import com.puppetlabs.geppetto.forge.client.OAuthModule;

public class ForgePublisherCallable extends ForgeServiceCallable<Diagnostic> {
	private static final long serialVersionUID = -4124246662292755617L;

	private static final String FORGE_CLIENT_ID = "cac18b1f07f13a244c47644548b29cbbe58048f3aaccdeefa7c0306467afda44";

	private static final String FORGE_CLIENT_SECRET = "2227c9a7392382f58b5e4d084b705827cb574673ff7d2a5905ef21685fd48e40";

	private String forgeLogin;

	private String forgePassword;

	public ForgePublisherCallable() {
	}

	public ForgePublisherCallable(String forgeLogin, String forgePassword, String forgeServiceURL,
			String repositoryURL, String branchName) {
		super(forgeServiceURL, repositoryURL, branchName);
		this.forgeLogin = forgeLogin;
		this.forgePassword = forgePassword;
	}

	@Override
	protected void addModules(Diagnostic diagnostic, List<Module> modules) {
		super.addModules(diagnostic, modules);

		if(forgeLogin == null || forgeLogin.length() == 0)
			diagnostic.addChild(new Diagnostic(ERROR, FORGE, "login must be specified"));

		if(forgePassword == null || forgePassword.length() == 0)
			diagnostic.addChild(new Diagnostic(ERROR, FORGE, "password must be specified"));

		if(diagnostic.getSeverity() >= ERROR)
			return;

		modules.add(new OAuthModule(FORGE_CLIENT_ID, FORGE_CLIENT_SECRET, forgeLogin, forgePassword));
	}

	@Override
	protected Diagnostic invoke(VirtualChannel channel) throws IOException, InterruptedException {
		Diagnostic result = new Diagnostic();
		Collection<File> moduleRoots = findModuleRoots(result);
		if(moduleRoots.isEmpty()) {
			result.addChild(new Diagnostic(Diagnostic.ERROR, Forge.PACKAGE, "No modules found in repository"));
			return result;
		}

		File buildDir = getBuildDir();
		buildDir.mkdirs();
		File builtModules = new File(buildDir, "builtModules");
		if(!(builtModules.mkdir() || builtModules.isDirectory())) {
			result.addChild(new Diagnostic(Diagnostic.ERROR, Forge.PACKAGE, "Unable to create directory" +
					builtModules.getPath()));
			return result;
		}

		List<File> tarBalls = new ArrayList<File>();
		Forge forge = getForge(result);
		for(File moduleRoot : moduleRoots)
			tarBalls.add(forge.build(moduleRoot, builtModules, null, null, null, result));

		getForgeService(result).publishAll(tarBalls.toArray(new File[tarBalls.size()]), false, result);
		return result;
	}
}
