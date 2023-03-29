/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2020-2022 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.configuration.providers.forge;

import java.io.File;
import java.io.Reader;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.fabricmc.loom.util.FileSystemUtil;

import org.gradle.api.Project;

import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.configuration.DependencyInfo;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.ZipUtils;

public class ForgeUserdevProvider extends DependencyProvider {
	private List<Pattern> filters;
	private File userdevJar;
	private JsonObject json;
	private Path accessTransformerConfig;
	private boolean notchObfPatches;
	Path joinedPatches;
	BinaryPatcherConfig binaryPatcherConfig;

	public ForgeUserdevProvider(Project project) {
		super(project);
	}

	@Override
	public void provide(DependencyInfo dependency) throws Exception {
		userdevJar = new File(getExtension().getForgeProvider().getGlobalCache(), "forge-userdev.jar");
		joinedPatches = getExtension().getForgeProvider().getGlobalCache().toPath().resolve("patches-joined.lzma");
		Path configJson = getExtension().getForgeProvider().getGlobalCache().toPath().resolve("forge-config.json");

		if (!userdevJar.exists() || Files.notExists(configJson) || isRefreshDeps()) {
			File resolved = dependency.resolveFile().orElseThrow(() -> new RuntimeException("Could not resolve Forge userdev"));
			Files.copy(resolved.toPath(), userdevJar.toPath(), StandardCopyOption.REPLACE_EXISTING);

			try (FileSystem fs = FileSystems.newFileSystem(new URI("jar:" + resolved.toURI()), ImmutableMap.of("create", false))) {
				Files.copy(fs.getPath("config.json"), configJson, StandardCopyOption.REPLACE_EXISTING);
			}
		}

		try (Reader reader = Files.newBufferedReader(configJson)) {
			json = new Gson().fromJson(reader, JsonObject.class);
		}

		addDependency(json.get("mcp").getAsString(), Constants.Configurations.MCP_CONFIG);
		addDependency(json.get("mcp").getAsString(), Constants.Configurations.SRG);
		addDependency(json.get("universal").getAsString(), Constants.Configurations.FORGE_UNIVERSAL);

		if (json.has("universalFilters")) {
			filters = new ArrayList<>();

			for (JsonElement element : json.getAsJsonArray("universalFilters")) {
				filters.add(Pattern.compile(element.getAsString()));
			}
		}

		accessTransformerConfig = getExtension().getForgeProvider().getGlobalCache().toPath().resolve("at-conf.cfg");

		if (Files.notExists(accessTransformerConfig)) {
			try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(userdevJar.toPath(), false)) {
				for (JsonElement element : json.getAsJsonArray("ats")) {
					Files.write(accessTransformerConfig, fs.readAllBytes(element.getAsString()), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
				}
			}
		}

		notchObfPatches = json.has("notchObf") && json.getAsJsonPrimitive("notchObf").getAsBoolean();

		if (Files.notExists(joinedPatches)) {
			Files.write(joinedPatches, ZipUtils.unpack(userdevJar.toPath(), json.get("binpatches").getAsString()));
		}

		binaryPatcherConfig = BinaryPatcherConfig.fromJson(json.getAsJsonObject("binpatcher"));
	}

	public Path getAccessTransformerConfig() {
		return accessTransformerConfig;
	}

	public boolean isNotchObfPatches() {
		return notchObfPatches;
	}

	public List<Pattern> getUniversalFilters() {
		return filters == null ? Collections.emptyList() : filters;
	}

	public File getUserdevJar() {
		return userdevJar;
	}

	@Override
	public String getTargetConfig() {
		return Constants.Configurations.FORGE_USERDEV;
	}

	public JsonObject getJson() {
		return json;
	}

	public record BinaryPatcherConfig(String dependency, List<String> args) {
		public static BinaryPatcherConfig fromJson(JsonObject json) {
			String dependency = json.get("version").getAsString();
			List<String> args = List.of(LoomGradlePlugin.GSON.fromJson(json.get("args"), String[].class));
			return new BinaryPatcherConfig(dependency, args);
		}
	}
}
