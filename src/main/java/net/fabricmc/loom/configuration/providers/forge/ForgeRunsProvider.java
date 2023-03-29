/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022 FabricMC
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.gradle.api.Project;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.ForgeLocalMod;
import net.fabricmc.loom.api.ModSettings;
import net.fabricmc.loom.configuration.ide.RunConfigSettings;
import net.fabricmc.loom.configuration.launch.LaunchProviderSettings;
import net.fabricmc.loom.extension.ForgeExtensionImpl;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.DependencyDownloader;
import net.fabricmc.loom.util.gradle.SourceSetHelper;

public class ForgeRunsProvider {
	private final Project project;
	private final LoomGradleExtension extension;
	private final JsonObject json;

	private final DependencyProvider dependencyProvider;

	public ForgeRunsProvider(Project project, JsonObject json) {
		this.project = project;
		this.extension = LoomGradleExtension.get(project);
		this.json = json;
	}

	public static void provide(Project project) {
		LoomGradleExtension extension = LoomGradleExtension.get(project);
		JsonObject json = extension.getForgeUserdevProvider().getJson();
		ForgeRunsProvider provider = new ForgeRunsProvider(project, json);

		for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("runs").entrySet()) {
			LaunchProviderSettings launchSettings = extension.getLaunchConfigs().findByName(entry.getKey());
			RunConfigSettings settings = extension.getRunConfigs().findByName(entry.getKey());
			JsonObject value = entry.getValue().getAsJsonObject();

			if (launchSettings != null) {
				launchSettings.evaluateLater(() -> {
					if (value.has("args")) {
						launchSettings.arg(StreamSupport.stream(value.getAsJsonArray("args").spliterator(), false)
								.map(JsonElement::getAsString)
								.map(provider::processTemplates)
								.collect(Collectors.toList()));
					}

					if (value.has("props")) {
						for (Map.Entry<String, JsonElement> props : value.getAsJsonObject("props").entrySet()) {
							String string = provider.processTemplates(props.getValue().getAsString());

							launchSettings.property(props.getKey(), string);
						}
					}
				});
			}

			if (settings != null) {
				settings.evaluateLater(() -> {
					settings.defaultMainClass(value.getAsJsonPrimitive("main").getAsString());

					if (value.has("jvmArgs")) {
						settings.vmArgs(StreamSupport.stream(value.getAsJsonArray("jvmArgs").spliterator(), false)
								.map(JsonElement::getAsString)
								.map(provider::processTemplates)
								.collect(Collectors.toList()));
					}

					if (value.has("env")) {
						for (Map.Entry<String, JsonElement> env : value.getAsJsonObject("env").entrySet()) {
							String string = provider.processTemplates(env.getValue().getAsString());

							settings.envVariables.put(env.getKey(), string);
						}
					}
				});
			}
		}
	}

	public String processTemplates(String string) {
		if (string.startsWith("{")) {
			String key = string.substring(1, string.length() - 1);

			// TODO: Look into ways to not hardcode
			if (key.equals("runtime_classpath")) {
				string = runtimeClasspath().stream()
						.map(File::getAbsolutePath)
						.collect(Collectors.joining(File.pathSeparator));
			} else if (key.equals("minecraft_classpath")) {
				string = minecraftClasspath().stream()
						.map(File::getAbsolutePath)
						.collect(Collectors.joining(File.pathSeparator));
			} else if (key.equals("runtime_classpath_file")) {
				Path path = extension.getFiles().getProjectPersistentCache().toPath().resolve("forge_runtime_classpath.txt");

				try {
					Files.writeString(path, runtimeClasspath().stream()
									.map(File::getAbsolutePath)
									.collect(Collectors.joining("\n")),
							StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}

				string = path.toAbsolutePath().toString();
			} else if (key.equals("minecraft_classpath_file")) {
				Path path = extension.getFiles().getProjectPersistentCache().toPath().resolve("forge_minecraft_classpath.txt");

				try {
					Files.writeString(path, minecraftClasspath().stream()
									.map(File::getAbsolutePath)
									.collect(Collectors.joining("\n")),
							StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}

				string = path.toAbsolutePath().toString();
			} else if (key.equals("asset_index")) {
				string = extension.getMinecraftProvider().getVersionInfo().assetIndex().fabricId(extension.getMinecraftProvider().minecraftVersion());
			} else if (key.equals("assets_root")) {
				string = new File(extension.getFiles().getUserCache(), "assets").getAbsolutePath();
			} else if (key.equals("natives")) {
				string = extension.getFiles().getNativesDirectory(project).getAbsolutePath();
			} else if (key.equals("source_roots")) {
				// Use a set-valued multimap for deduplicating paths.
				// It could be done using Stream.distinct before but that doesn't work if
				// you have *both* a ModSettings and a ForgeLocalMod with the same name.
				Multimap<String, String> modClasses = MultimapBuilder.hashKeys().linkedHashSetValues().build();

				for (ModSettings mod : extension.getMods()) {
					for (File file : SourceSetHelper.getClasspath(mod, project)) {
						modClasses.put(mod.getName(), file.getAbsolutePath());
					}
				}

				for (ForgeLocalMod localMod : ((ForgeExtensionImpl) extension.getForge()).localMods) {
					String sourceSetName = localMod.getName();

					localMod.getSourceSets().flatMap(sourceSet -> Stream.concat(
							Stream.of(sourceSet.getOutput().getResourcesDir()),
							sourceSet.getOutput().getClassesDirs().getFiles().stream())
					).map(File::getAbsolutePath).forEach(path -> modClasses.put(sourceSetName, path));
				}

				string = modClasses.entries().stream()
						.map(entry -> entry.getKey() + "%%" + entry.getValue())
						.collect(Collectors.joining(File.pathSeparator));
			} else if (key.equals("mcp_mappings")) {
				string = "loom.stub";
			} else if (key.equals("mcp_to_srg")) {
				string = dependencyProvider.getExtension().getMappingsProvider().getMcpToSrg().toAbsolutePath().toString();
			} else if (json.has(key)) {
				JsonElement element = json.get(key);

				if (element.isJsonArray()) {
					string = StreamSupport.stream(element.getAsJsonArray().spliterator(), false)
							.map(JsonElement::getAsString)
							.flatMap(str -> {
								if (str.contains(":")) {
									return DependencyDownloader.download(project, str, false, false).getFiles().stream()
											.map(File::getAbsolutePath)
											.filter(dep -> !dep.contains("bootstraplauncher")); // TODO: Hack
								}

								return Stream.of(str);
							})
							.collect(Collectors.joining(File.pathSeparator));
				} else {
					string = element.toString();
				}
			} else {
				project.getLogger().warn("Unrecognized template! " + string);
			}
		}

		return string;
	}

	private Set<File> runtimeClasspath() {
		// Should we actually include the runtime classpath here? Forge doesn't seem to be using this property anyways
		return minecraftClasspath();
	}

	private Set<File> minecraftClasspath() {
		return DependencyDownloader.resolveFiles(project, project.getConfigurations().getByName(Constants.Configurations.FORGE_RUNTIME_LIBRARY), true);
	}
}
