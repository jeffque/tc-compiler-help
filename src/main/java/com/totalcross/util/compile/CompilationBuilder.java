package com.totalcross.util.compile;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Predicate;

import totalcross.io.File;
import totalcross.io.RandomAccessStream;
import totalcross.ui.MainWindow;

public class CompilationBuilder {
	public static String toTczLibName(String jarName) {
		return jarName.replaceFirst("\\.jar$", "Lib.tcz");
	}
	
	public static String getDepTczName(String tczName) {
		return tczName.replaceAll("^.*[/\\\\]([^/\\\\]*Lib.tcz)$", "$1");
	}
	
	private String key;
	private String totalcrossHome;
	private List<String> args = new ArrayList<>();
	private Map<String,String> enviroment = new HashMap<>();
	private String mainTargetPath;
	private Predicate<String> mustCompile = (p) -> false;
	private List<AvailablePlatforms> platformsToBuild = new ArrayList<>();
	
	private Boolean isWindows = null;
	private List<String> classPathList = null;
	private String classPath = null;
	private List<String> depsPathGenerated = new ArrayList<>();
	
	private boolean isWindows() {
		if (isWindows == null) {
			isWindows = System.getProperty("os.name").toLowerCase().contains("win");
		}
		
		return isWindows;
	}
	
	public CompilationBuilder() {
	}
	
	public CompilationBuilder setArgs(List<String> args) {
		this.args = args;
		
		return this;
	}
	
	public CompilationBuilder addArgs(String... newArgs) {
		for (String arg: newArgs) {
			args.add(arg);
		}
		
		return this;
	}
	
	/**
	 * Set environment variable TOTALCROSS3_HOME. Leave it null to get from environment.
	 */
	public CompilationBuilder setTotalCrossHome(String totalcrossHome) {
		this.totalcrossHome = totalcrossHome;
		
		return this;
	}
	
	/**
	 * Set your own TotalCross key for the desired build.
	 */
	public CompilationBuilder setKey(String key) {
		this.key = key;
		
		return this;
	}
	
	public CompilationBuilder setMainTarget(Class<? extends MainWindow> mainWindowClass) {
		return setMainTarget(mainWindowClass, ".jar");
	}
	
	public CompilationBuilder setMainTarget(Class<? extends MainWindow> mainWindowClass, String extension) {
		return setMainTarget(mainWindowClass, extension, "target/");
	}

	public CompilationBuilder setMainTarget(Class<? extends MainWindow> mainWindowClass, String extension, String prefixPath) {
		String className = mainWindowClass.getName().replaceAll("^.*\\.", "");
		mainTargetPath = prefixPath + className + extension;
		return this;
	}
	
	public CompilationBuilder setMustCompile(Predicate<String> mustCompile) {
		this.mustCompile = mustCompile;
		return this;
	}
	
	public CompilationBuilder build() throws IOException, InterruptedException {
		for (String classPathElement: getClassPathList()) {
			if (mustCompile.test(classPathElement)) {
				generateTcz(classPathElement);
			}
		}
		return callDeploy(mainTargetPath, getPlatformListString());
	}
	
	private List<String> getPlatformListString() {
		List<String> platformListString = new ArrayList<>();
		for (AvailablePlatforms platform: platformsToBuild) {
			platformListString.add(platform.toFlag());
		}
		return platformListString;
	}
	
	public CompilationBuilder setName(String name) {
		return addArgs("/n", name);
	}
	
	public CompilationBuilder setOutputDirectory(String outputDirectory) {
		return addArgs("/o", outputDirectory);
	}
	
	public CompilationBuilder setCommand(String cmd) {
		return addArgs("/c", cmd);
	}
	
	public CompilationBuilder setMobileprovisionPath(String mobileprovisionPath) {
		return addArgs("/m", mobileprovisionPath);
	}
	
	public CompilationBuilder addPlatformsTarget(AvailablePlatforms... platformsTarget) {
		for (AvailablePlatforms platformTarget: platformsTarget) {
			platformsToBuild.add(platformTarget);
		}
		return this;
	}

	public CompilationBuilder callDeploy(String target, List<String> platforms) throws IOException, InterruptedException {
		try (Closeable c = manageAllPkg()) {
			innerCallDeploy(target, args, platforms);
			return this;
		}
	}
	
	private static final String ALL_PKG = "all.pkg";
	private static final String ALL_PKG_BKP = ALL_PKG + "-bkp";
	
	private Closeable manageAllPkg() throws IOException {
		Closeable ret = () -> {};
		if (!mustOverwriteAllPkg()) {
			return ret;
		}
		try {
			int fileMode = File.CREATE_EMPTY;
			if (new File(ALL_PKG, File.DONT_OPEN).exists()) {
				fileMode = File.READ_WRITE;
				File.copy(ALL_PKG, ALL_PKG_BKP);
				ret = () -> {
					try {
						File.move(ALL_PKG_BKP, ALL_PKG);
					} catch (totalcross.io.IOException e) {
						throw new IOException(e);
					}
				};
			} else {
				ret = () -> {
					try {
						new File(ALL_PKG, File.DONT_OPEN).delete();
					} catch (totalcross.io.IOException e) {
						throw new IOException(e);
					}
				};
			}
			
			try (ACFile f = new ACFile(openFile(ALL_PKG, fileMode));
					OutputStream os = f.f.asOutputStream();
					) {
				if (fileMode == File.READ_WRITE) {
					os.write('\n');
				}
				for (String depsGenerated: depsPathGenerated) {
					os.write(("[L] " + depsGenerated + "\n").getBytes());
				}
			}
		} catch (totalcross.io.IOException e) {
			throw new IOException(e);
		}
		return ret;
	}
	
	private boolean mustOverwriteAllPkg() {
		return depsPathGenerated.size() != 0;
	}

	private File openFile(String fileName, int fileMode) throws totalcross.io.IOException {
		File f = new File(fileName, fileMode);
		
		if (fileMode == File.READ_WRITE && f.getSize() != 0) {
			f.setPos(0, RandomAccessStream.SEEK_END);
		}
		return f;
	}

	private static class ACFile implements Closeable {
		final File f;
		
		ACFile(File f) {
			this.f = f;
		}
		
		@Override
		public void close() throws IOException {
			try {
				f.close();
			} catch (totalcross.io.IOException e) {
				throw new IOException(e);
			}
		}
		
	}

	private void innerCallDeploy(String target, List<String> args, List<String> platforms) throws IOException, InterruptedException {
		if (key == null) {
			throw new NullPointerException("Must set 'key' value before build");
		}
		String classpath = getClassPath();
		
		List<String> cmdLine = new ArrayList<>();
		
		cmdLine.add("java");
		cmdLine.add("-cp");
		cmdLine.add(classpath);
		cmdLine.add("tc.Deploy");
		cmdLine.add(target);
		cmdLine.add("/r");
		cmdLine.add(key);
		
		for (String platform: platforms) {
			cmdLine.add("-" + platform);
		}
		for (String arg: args) {
			cmdLine.add(arg);
		}
		ProcessBuilder builder = new ProcessBuilder(cmdLine.toArray(new String[0]));
		builder.inheritIO();
		if (totalcrossHome != null) {
			builder.environment().put("TOTALCROSS3_HOME", totalcrossHome);
		}
		
		for (Entry<String, String> envVar: enviroment.entrySet()) {
			builder.environment().put(envVar.getKey(), envVar.getValue());
		}
		Process process = builder.start();
		process.waitFor();
	}
	
	private void generateTcz(String target) throws IOException, InterruptedException {
		String tczName = toTczLibName(target);
		depsPathGenerated.add(tczName);
		
		innerCallDeploy(target, getArgsToLib(tczName), new ArrayList<>());
	}
	
	private List<String> getArgsToLib(String tczName) {
		List<String> newArgs = new ArrayList<>();
		for (String arg: args) {
			newArgs.add(arg);
		}
		newArgs.add("/n");
		newArgs.add(getDepTczName(tczName));
		return newArgs;
	}

	private String getPath(String path) {
		if (isWindows()) {
			path = path.substring(1).replace('/', '\\');
		}
		return path.replaceAll("%20", " ");
	}

	private String getClasspathSeparator() {
		return isWindows()? ";": ":";
	}

	private String getClassPath() {
		if (classPath == null) {
			String classpathSeparator = getClasspathSeparator();
			boolean first = true;
			classPath = "";
			for (String classPathUnit: getClassPathList()) {
				if (first) {
					first = false;
				} else {
					classPath += classpathSeparator;
				}
				classPath += classPathUnit;
			}
		}
		return classPath;
	}
	
	private List<String> getClassPathList() {
		if (classPathList == null) {
			classPathList = new ArrayList<>();
			for (URL url: ((URLClassLoader) (Thread.currentThread().getContextClassLoader())).getURLs()) {
				classPathList.add(getPath(url.getPath()));
			}
		}
		return classPathList;
	}

	public CompilationBuilder setEnvVar(String key, String value) {
		enviroment.put(key, value);
		return this;
	}

	public CompilationBuilder singlePackage() {
		return addArgs("/p");
	}
}
