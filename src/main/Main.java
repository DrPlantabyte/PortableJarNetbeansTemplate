package main;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Entry point for the jar executable.
 */
public class Main {
	/** Name of the application, used for creating install folders */
	public static final String APP_NAME = "javaApp"; // change to name of this project
	
	/** Change this field to change how resources are extracted */
	private static RunType INSTALL_MODE = RunType.TEMP_FOLDER;
	/** The properties returned by <code> System.getProperties();</code> */
	private static Properties SYSTEM_PROPERTIES = new Properties();
	/** This is the location where the files will be unpacked. */
	private static Path PROGRAM_DIRECTORY = (new File(".")).toPath();
	/** If true, delete unpacked files on JVM exit. */
	private static boolean DELETE_ON_EXIT = false;
	/** If true, overwrite existing unpacked files. */
	private static boolean OVERWRITE_RESOURCES = true;
	/** JarFile instance pointing to the .jar file */
	private static JarFile JAR_FILE = null;
	/** Lock used to prevent spawned threadsfrom getting ahead of the initializer */
	private static final Lock initLock = new ReentrantLock();
	
	private static AtomicBoolean initializationComplete = new AtomicBoolean (false);
	
	public static void main(String[] args){
		try {
			initLock.lock();
			Logger.getLogger(Main.class.getName()).log(Level.INFO,"Preparing to launch "+APP_NAME);
			readEnv();
			unpack();
			initializationComplete.set(true);
			Logger.getLogger(Main.class.getName()).log(Level.FINE,"\tAPP_NAME: "+APP_NAME);
			Logger.getLogger(Main.class.getName()).log(Level.FINE,"\tINSTALL_MODE: "+INSTALL_MODE.name());
			Logger.getLogger(Main.class.getName()).log(Level.FINE,"\tPROGRAM_DIRECTORY: "+PROGRAM_DIRECTORY);
			Logger.getLogger(Main.class.getName()).log(Level.FINE,"\tDELETE_ON_EXIT: "+DELETE_ON_EXIT);
			Logger.getLogger(Main.class.getName()).log(Level.FINE,"\tOVERWRITE_RESOURCES: "+OVERWRITE_RESOURCES);
			Logger.getLogger(Main.class.getName()).log(Level.FINE,"\tJAR_FILE: "+JAR_FILE.getName());
			Logger.getLogger(Main.class.getName()).log(Level.FINEST,"\tSYSTEM_PROPERTIES: \n"+SYSTEM_PROPERTIES);
		} catch (IOException | SecurityException | IllegalArgumentException ex) {
			Logger.getLogger(Main.class.getName()).log(Level.SEVERE, "Failed to initialize program", ex);
			System.exit(ex.getClass().getName().hashCode());
		}finally{
			initLock.unlock();
		}
		Logger.getLogger(Main.class.getName()).log(Level.INFO,"Launching "+APP_NAME);
	//	myapp.MyMain.main(args); // uncomment and replace with your main method invocation
	}
	/**
	 * Reads the environment variables and sets parameters based on host system data.
	 * @throws IOException Thrown if there was a problem reading or writing a file
	 */
	private static void readEnv() throws IOException{
		try {
			if (System.getProperties() != null) {
				SYSTEM_PROPERTIES = System.getProperties();
			}
		} catch (SecurityException ex) {
			Logger.getLogger(Main.class.getName()).log(Level.WARNING, "Security manager prevented access to System.getProperties()", ex);
		}
		Path jarDir = null;
		try {
				JAR_FILE = getJarFile();
				jarDir = getJarDirectory();
				if(JAR_FILE == null){
					throw new FileNotFoundException("Code source .jar file was not found");
				}
			} catch (FileNotFoundException ex) {
				throw new IOException("Unable to access source .jar file. Code may not be running from a .jar file", ex);
			}
		
		try{
			switch(INSTALL_MODE){
				case TEMP_FOLDER:
					PROGRAM_DIRECTORY = Files.createTempDirectory(APP_NAME);
					DELETE_ON_EXIT = true;
					OVERWRITE_RESOURCES = true;
					break;
				case TRANSIENT_PORTABLE:
					PROGRAM_DIRECTORY = jarDir;
					DELETE_ON_EXIT = true;
					OVERWRITE_RESOURCES = true;
					break;
				case PERSISTANT_PORTABLE:
					PROGRAM_DIRECTORY = jarDir;
					DELETE_ON_EXIT = false;
					OVERWRITE_RESOURCES = false;
					break;
				case PERSISTANT_INSTALL:
					switch(SYSTEM_PROPERTIES.getProperty("os.name")){
						case ("Windows Vista"):
						case ("Windows 7"):
						case ("Windows 8"):
						case ("Windows NT"):
							PROGRAM_DIRECTORY = Paths.get(SYSTEM_PROPERTIES.getProperty("user.home"),"AppData","Roaming",APP_NAME);
							break;
						case ("Windows XP"):
						case ("Windows 95"):
						case ("Windows 98"):
						case ("Windows 2000"):
							PROGRAM_DIRECTORY = Paths.get(SYSTEM_PROPERTIES.getProperty("user.home"),"Application Data",APP_NAME);
							break;
						case ("Mac OS X"):
							PROGRAM_DIRECTORY = Paths.get(SYSTEM_PROPERTIES.getProperty("user.home"),"Library","Application Support",APP_NAME);
							break;
						case ("Linux"):
						case ("Unix"):
						case ("FreeBSD"):
						case ("Digital Unix"):
						case ("Solaris"):
							PROGRAM_DIRECTORY = Paths.get(SYSTEM_PROPERTIES.getProperty("user.home"),"."+APP_NAME);
							break;
						default:
							// unknown/unsupported OS
							Logger.getLogger(Main.class.getName()).log(Level.WARNING, 
								"OS type '"+SYSTEM_PROPERTIES.getProperty("os.name")
										+"' is not yet supported. Program will install into local directory instead of user home.");
								PROGRAM_DIRECTORY = jarDir;
							break;
					}
					DELETE_ON_EXIT = false;
					OVERWRITE_RESOURCES = false;
					break;
			}
		}catch(IOException ex){
			Logger.getLogger(Main.class.getName()).log(Level.WARNING, 
						"Unable to create proper environment for run type '"+ INSTALL_MODE.name()+"', defaulting to transient mode in working directory", ex);
			// reset to defaults
			PROGRAM_DIRECTORY = (new File(".")).toPath();
			DELETE_ON_EXIT = false;
			OVERWRITE_RESOURCES = true;
		}
		
		if(DELETE_ON_EXIT){
			PROGRAM_DIRECTORY.toFile().deleteOnExit();
		}
	}
	/**
	 * Extracts the resources to a folder and the extracted lib folder to the class loaders.
	 * @throws IOException Thrown if there was a problem reading or writing files
	 * @throws SecurityException Thrown if a security manager prohibits changes to runtime environment
	 * @throws IllegalArgumentException Thrown if an atypical JVM configuration prevents modification to runtime environment
	 */
	private static void unpack() throws IOException, SecurityException, IllegalArgumentException {
		Files.createDirectories(PROGRAM_DIRECTORY);
		
		// unpack libs and add them to classpath
		Path libDir = Paths.get(PROGRAM_DIRECTORY.toString(), "lib");
		Files.createDirectories(libDir);
		List<Path> extractedLibs = extractFolderFromJar(JAR_FILE,"lib",libDir,OVERWRITE_RESOURCES,DELETE_ON_EXIT);
		for(Path f : extractedLibs){
			Logger.getLogger(Main.class.getName()).log(Level.FINE, "Extracted file '"+f.toString()+"' from '"+JAR_FILE.getName()+"'");
		}
		addLibraryToClassLoader(libDir); // add lib folder itself to class loader
		for(Path f : extractedLibs){
			if(f.toString().toLowerCase(Locale.US).endsWith(".jar")){
				// jar file, add to class loader
				addLibraryToClassLoader(f);
			}
		}
		
		// unpack resources (aka data)
		Path resourceDir = Paths.get(PROGRAM_DIRECTORY.toString(), "data");
		Files.createDirectories(resourceDir);
		List<Path> extractedResources = extractFolderFromJar(JAR_FILE,"data",resourceDir,OVERWRITE_RESOURCES,DELETE_ON_EXIT);
		for(Path f : extractedResources){
			Logger.getLogger(Main.class.getName()).log(Level.FINE, "Extracted file '"+f.toString()+"' from '"+JAR_FILE.getName()+"'");
		}
	}
	/**
	 * Gets the specified resource by a filepath where each level in the 
	 * directory structure is an argument. Foe example, to get "img/pic.png", 
	 * you'd invoke <code>getResource("img","pic.png")</code> and this method 
	 * would return "<u>&gt;program direcotry&lt;</u>/data/img/pic.png"
	 * <p/>
	 * More specifically, this method returns 
	 * <code>Paths.get(Paths.get(PROGRAM_DIRECTORY.toString(),"data").toString(),filepath);</code>
	 * @param filepath The file path, with multiple arguments to represent the 
	 * path in the place of using the OS-dependant file separator.
	 * @return A Path pointing to the indicated resource. 
	 */
	public Path getResource(String... filepath){
		if(initializationComplete.get() == false){
			initLock.tryLock(); // wait for init to complete
			initLock.unlock();
		}
		return Paths.get(Paths.get(PROGRAM_DIRECTORY.toString(),"data").toString(),filepath);
	}
	/**
	 * Adds a given file to the system classloader using reflection.
	 * @param file File to add to classpath. 
	 * <b>MUST BE EITHER A FOLDER OR A .jar FILE!!!</b>
	 * @throws SecurityException Thrown if the security manager prevents 
	 * reflection access to the classloader
	 * @throws IllegalArgumentException Thrown if other problems prevent java 
	 * reflection from adding URLs to the runtime classloader (e.g. if the 
	 * system classloader is not a URL classloader).
	 */
	private static void addLibraryToClassLoader(Path file) throws SecurityException, IllegalArgumentException  {
		try{
			java.lang.reflect.Method method = java.net.URLClassLoader.class.getDeclaredMethod("addURL", new Class[]{java.net.URL.class});
			method.setAccessible(true);
			method.invoke(ClassLoader.getSystemClassLoader(), new Object[]{file.toUri().toURL()});
			method.setAccessible(false);
		} catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | MalformedURLException ex) {
			throw new IllegalArgumentException("Could not invoke method 'addURL(java.net.URL)' on class 'java.net.URLClassLoader' from 'ClassLoader.getSystemClassLoader()'");
		}
	}
	/**
	 * Gets the filepath of this executable jar.
	 * @return A Path object pointing to the location of this executable.
	 */
	public static Path getJarDirectory() throws FileNotFoundException, IOException{
		File f = null;
	//	try {
			f = new File(getJarFile().getName());
	//	} catch (URISyntaxException ex) {
	//		Logger.getLogger(Main.class.getName()).log(Level.SEVERE, null, ex);
	//	}
		if(f == null || f.exists() == false){
			throw new FileNotFoundException("Directory '" + f.toString() + "' does not exist!");
		}
		return f.getParentFile().toPath();
	}
	
	/**
	 * Gets the location of the .jar file holding the code for this class. 
	 * @return A JarFile instance pointing to the code source, if it is a .jar 
	 * file. If not, then 
	 * @throws IOException Thrown if there was an error reading the .jar code 
	 * source.
	 * @throws FileNotFoundException Thrown if the code is not running from a 
	 * .jar file
	 * @throws SecurityException Thrown if the security manager prohibits access 
	 * to the code source.
	 */
	public static JarFile getJarFile() throws IOException, FileNotFoundException{
		try {
			return new java.util.jar.JarFile(new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
		} catch (URISyntaxException ex) {
			throw new IOException("Error parsing URI '"+Main.class.getProtectionDomain().getCodeSource().getLocation().toString()+"'");
		}
	}

	
	
	/**
	 * This enum is used to specify how this program should manage its extracted 
	 * resources 
	 */
	static enum RunType{
		/** Extracts resources to temporary folder and deletes them when JVM exits */
		TEMP_FOLDER,
		/** Extracts resources to local folder, overwritting previous files */
		TRANSIENT_PORTABLE,
		/** Uses already existing files in local folder, overwriting only those 
		 * files that do not already exist */
		PERSISTANT_PORTABLE,
		 /** Extracts resource files to the OS standard "App Data" location, 
		  * using existing files instead of overwriting them. Defaults to 
		  * PERSISTANT_PORTABLE mode if the host OS is not supported. */
		PERSISTANT_INSTALL
	}
	
	
	/**
	* Unpacks a specified folder from the given .jar file into the specified 
	* directory.
	* @param jar The .jar file that holds the directory to be extracted
	* @param pathInsideJar The path inside the jar file to the directory you 
	* want to extract, in posix format (i.e. uses / as the file separator 
	* character)
	* @param destinationDir The folder to extract to (must exist!)
	 * @param overwrite if true, then existing files will be overwritten.
	 * @param deleteOnClose if true, the JVM will delete the files on exit
	* @return The files that were extracted
	* @throws IOException Thrown if there was an IO error
	* @throws FileNotFoundException Thrown if the destination folder does not exist
	*/
	public static List<Path> extractFolderFromJar(java.util.jar.JarFile jar, String pathInsideJar, Path destinationDir, boolean overwrite, boolean deleteOnClose) throws IOException, FileNotFoundException {
		if (Files.isDirectory(destinationDir) == false) {
			throw new FileNotFoundException("'" + destinationDir + "' is not an existing directory!");
		}
		if(pathInsideJar.endsWith("/") == false ){
			pathInsideJar = pathInsideJar + "/";
		}
		java.util.ArrayList<Path> unpackedFiles = new java.util.ArrayList<>();
		java.util.Enumeration jarItems = jar.entries();
		while (jarItems.hasMoreElements()) {
			java.util.jar.JarEntry file = (java.util.jar.JarEntry) jarItems.nextElement();
			if (file.getName().startsWith(pathInsideJar)) {
				java.io.File outputFile = new java.io.File(destinationDir.toString() + java.io.File.separator + file.getName().substring(pathInsideJar.length(), file.getName().length()));
				if(deleteOnClose){
					outputFile.deleteOnExit();
				}
				
				if (file.isDirectory()) { // if its a directory, create it
					outputFile.mkdirs();
					continue;
				} else if (outputFile.getParentFile().exists() == false) {
					outputFile.getParentFile().mkdirs();
				}
				
				// outputFile guarenteed not to be a directory at this point
				if(overwrite == false && outputFile.exists()){
					// do not overwrite the destination file
					continue;
				}
				
				java.io.InputStream is = new BufferedInputStream(jar.getInputStream(file)); // get the input stream
				java.io.OutputStream fos = new BufferedOutputStream(new java.io.FileOutputStream(outputFile));
				while (is.available() > 0) {  // write contents of 'is' to 'fos'
					fos.write(is.read());
				}
				fos.flush();
				fos.close();
				is.close();
				unpackedFiles.add(outputFile.toPath());
			}
		}
		return unpackedFiles;
	}
	
	
}
