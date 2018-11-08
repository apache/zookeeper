## Generating the static Apache ZooKeeper website

In the `src/main/resources/markdown` directory you will find text files formatted using Markdown, with an `.md` suffix.

Building the site requires [Maven](http://maven.apache.org/) 3.5.0 or newer. 
The easiest way to [install Maven](http://maven.apache.org/install.html) depends on your OS.
The build process will create a directory called `target/html` containing `index.html` as well as the rest of the
compiled directories and files. `target` should not be committed to git as it is generated content.

You can generate the static ZooKeeper website by running:

1. `mvn clean install` in this directory.
2. `cp -RP _released_docs _target/html` - this will include the documentation (see "sub-dir" section below) in the generated site.

At this point the contents of `target` are "staged" and can be reviewed prior to updating the ZooKeeper
production website.

## Docs sub-dir

The product documentation is not generated as part of the website. They are built separately for each release 
of ZooKeeper from the ZooKeeper source repository.

Typically during a release the versioned documentation will be recreated and should be copied, and committed,
under the `_released_docs` directory here.
