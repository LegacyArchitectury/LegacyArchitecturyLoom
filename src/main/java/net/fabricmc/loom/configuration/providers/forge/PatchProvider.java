/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2020-2023 FabricMC
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import lzma.sdk.lzma.Decoder;
import lzma.sdk.lzma.Encoder;
import lzma.streams.LzmaInputStream;
import lzma.streams.LzmaOutputStream;

import net.fabricmc.loom.configuration.providers.forge.legacy.Pack200Provider;

import org.apache.commons.io.IOUtils;
import org.gradle.api.Project;

import net.fabricmc.loom.configuration.DependencyInfo;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.FileSystemUtil;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;

public class PatchProvider extends DependencyProvider {
	public Path clientPatches;
	public Path serverPatches;
	public Path mergedPatches;

	public PatchProvider(Project project) {
		super(project);
	}

	@Override
	public void provide(DependencyInfo dependency) throws Exception {
		init();

		if (Files.notExists(clientPatches) || Files.notExists(serverPatches) || refreshDeps()) {
			getProject().getLogger().info(":extracting forge patches");

			Path installerJar = getExtension().isModernForge()
					? dependency.resolveFile().orElseThrow(() -> new RuntimeException("Could not resolve Forge installer")).toPath()
					: getExtension().getForgeUniversalProvider().getForge().toPath();

			try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(installerJar, false)) {
				if (getExtension().isModernForge()) {
					Files.copy(fs.getPath("data", "client.lzma"), clientPatches, StandardCopyOption.REPLACE_EXISTING);
					Files.copy(fs.getPath("data", "server.lzma"), serverPatches, StandardCopyOption.REPLACE_EXISTING);
				} else {
					splitAndConvertLegacyPatches(fs.getPath("binpatches.pack.lzma"));
				}
			}
		}
	}

	private void init() throws IOException {
		final Path projectCacheFolder = ForgeProvider.getForgeCache(getProject());
		clientPatches = projectCacheFolder.resolve("patches-client.lzma");
		serverPatches = projectCacheFolder.resolve("patches-server.lzma");
		if (getExtension().isLegacyForge()) {
			mergedPatches = projectCacheFolder.resolve("patches-merged.lzma");
		}

		try {
			Files.createDirectories(projectCacheFolder);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Override
	public String getTargetConfig() {
		return Constants.Configurations.FORGE_INSTALLER;
	}

	private void splitAndConvertLegacyPatches(Path joinedLegacyPatches) throws IOException {
		try (JarInputStream in = new JarInputStream(new ByteArrayInputStream(unpack200Lzma(joinedLegacyPatches)));
			 OutputStream clientFileOut = Files.newOutputStream(clientPatches, CREATE, TRUNCATE_EXISTING);
			 LzmaOutputStream clientLzmaOut = new LzmaOutputStream(clientFileOut, new Encoder());
			 JarOutputStream clientJarOut = new JarOutputStream(clientLzmaOut);
			 OutputStream serverFileOut = Files.newOutputStream(serverPatches, CREATE, TRUNCATE_EXISTING);
			 LzmaOutputStream serverLzmaOut = new LzmaOutputStream(serverFileOut, new Encoder());
			 JarOutputStream serverJarOut = new JarOutputStream(serverLzmaOut);
		) {
			for (JarEntry entry; (entry = in.getNextJarEntry()) != null;) {
				String name = entry.getName();

				JarOutputStream out;

				if (name.startsWith("binpatch/client/")) {
					out = clientJarOut;
				} else if (name.startsWith("binpatch/server/")) {
					out = serverJarOut;
				} else {
					getProject().getLogger().warn("Unexpected file in Forge binpatches archive: " + name);
					continue;
				}

				out.putNextEntry(new ZipEntry(name));

				// Converting from legacy format to modern (v1) format
				DataInputStream dataIn = new DataInputStream(in);
				DataOutputStream dataOut = new DataOutputStream(out);
				dataOut.writeByte(1); // version
				dataIn.readUTF(); // unused patch name (presumably always the same as the obf class name)
				dataOut.writeUTF(dataIn.readUTF().replace('.', '/')); // obf class name
				dataOut.writeUTF(dataIn.readUTF().replace('.', '/')); // srg class name
				IOUtils.copy(in, out); // remainder is unchanged

				out.closeEntry();
			}
		}

		try (JarInputStream in = new JarInputStream(new ByteArrayInputStream(unpack200Lzma(joinedLegacyPatches)));
			 OutputStream mergedFileOut = Files.newOutputStream(mergedPatches, CREATE, TRUNCATE_EXISTING);
			 LzmaOutputStream mergedLzmaOut = new LzmaOutputStream(mergedFileOut, new Encoder());
			 JarOutputStream mergedJarOut = new JarOutputStream(mergedLzmaOut);
		) {
			for (JarEntry entry; (entry = in.getNextJarEntry()) != null;) {
				String name = entry.getName();

				JarOutputStream out;

				if (name.startsWith("binpatch/client/")) {
					out = mergedJarOut;
				} else if (name.startsWith("binpatch/server/")) {
					out = mergedJarOut;
				} else {
					getProject().getLogger().warn("Unexpected file in Forge binpatches archive: " + name);
					continue;
				}

				out.putNextEntry(new ZipEntry(name));

				// Converting from legacy format to modern (v1) format
				DataInputStream dataIn = new DataInputStream(in);
				DataOutputStream dataOut = new DataOutputStream(out);
				dataOut.writeByte(1); // version
				dataIn.readUTF(); // unused patch name (presumably always the same as the obf class name)
				dataOut.writeUTF(dataIn.readUTF().replace('.', '/')); // obf class name
				dataOut.writeUTF(dataIn.readUTF().replace('.', '/')); // srg class name
				IOUtils.copy(in, out); // remainder is unchanged

				out.closeEntry();
			}
		}
	}

	private byte[] unpack200(InputStream in) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		try (JarOutputStream jarOut = new JarOutputStream(bytes)) {
			Pack200Provider provider = getExtension().getForge().getPack200Provider().getOrNull();

			if (provider == null) {
				throw new IllegalStateException("No provider for Pack200 has been found. Did you declare a provider?");
			}

			provider.unpack(in, jarOut);
		}

		return bytes.toByteArray();
	}

	private byte[] unpack200Lzma(InputStream in) throws IOException {
		try (LzmaInputStream lzmaIn = new LzmaInputStream(in, new Decoder())) {
			return unpack200(lzmaIn);
		}
	}

	private byte[] unpack200Lzma(Path path) throws IOException {
		try (InputStream in = Files.newInputStream(path)) {
			return unpack200Lzma(in);
		}
	}
}
