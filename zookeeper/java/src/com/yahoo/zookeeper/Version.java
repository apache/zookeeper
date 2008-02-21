package com.yahoo.zookeeper;

public class Version implements com.yahoo.zookeeper.version.Info{

	public static int getRevision() {
		return REVISION;
	}

	public static String getBuildDate() {
		return BUILD_DATE;
	}

	public static String getVersion() {
		return MAJOR + "." + MINOR + "." + MICRO;
	}

	public static String getVersionRevision() {
		return getVersion() + "-" + getRevision();
	}

	public static String getFullVersion() {
		return getVersionRevision() + ", built on " + getBuildDate();
	}

	public static void printUsage() {
		System.out
				.print("Usage:\tjava -cp ... com.yahoo.zookeeper.Version "
						+ "[--full | --short | --revision],\n\tPrints --full version "
						+ "info if no arg specified.");
		System.exit(1);
	}

	/**
	 * Prints the current version, revision and build date to the standard out.
	 * 
	 * @param args
	 *            <ul>
	 *            <li> --short - prints a short version string "1.2.3"
	 *            <li> --revision - prints a short version string with the SVN
	 *            repository revision "1.2.3-94"
	 *            <li> --full - prints the revision and the build date
	 *            </ul>
	 */
	public static void main(String[] args) {
		if (args.length > 1) {
			printUsage();
		}
		if (args.length == 0 || (args.length == 1 && args[0].equals("--full"))) {
			System.out.println(getFullVersion());
			System.exit(0);
		}
		if (args[0].equals("--short"))
			System.out.println(getVersion());
		else if (args[0].equals("--revision"))
			System.out.println(getVersionRevision());
		else
			printUsage();
		System.exit(0);
	}
}
