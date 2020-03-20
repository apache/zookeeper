## Generating the static Apache ZooKeeper website

In the `src/main/resources/markdown` directory you will find text files formatted using Markdown, with an `.md` suffix.

Building the site requires [Maven](https://maven.apache.org/) 3.5.0 or newer.
The easiest way to [install Maven](https://maven.apache.org/install.html) depends on your OS.
The build process will create a directory called `target/html` containing `index.html` as well as the rest of the
compiled directories and files. `target` should not be committed to git as it is generated content.

You can generate the static ZooKeeper website by running:

1. `mvn clean install` in this directory.
2. `cp -RP _released_docs target/html/doc` - this will include the documentation (see "sub-dir" section below) in the generated site.

At this point the contents of `target` are "staged" and can be reviewed prior to updating the ZooKeeper
production website.

## Docs sub-dir

The product documentation creation is not part of the website generation process. They are built separately for each release
of ZooKeeper from the ZooKeeper source repository.

Typically during a release the versioned documentation will be recreated and should be copied, and committed,
under the `_released_docs` directory here.

## Steps to update the website
1. `git clone -b website https://gitbox.apache.org/repos/asf/zookeeper.git`
2. update the appropriate pages, typically a markdown file e.g. credits.md, etc...
3.  `mvn clean install`
4. `cp -RP _released_docs target/html/doc` These are the static release docs, not generated in this process.

At this point verify that the generated files render properly (open target/html/index.html in a browser).
If you are happy with the results move on to the next step, otherwise go to step 2 above.

5. `git status` should show modified files for the markdown that you changed
6. `git add <the changed files>`
7. `git commit -m "<appropriate commit message>"`
8. `git push origin website`

The source for the site is committed, now we need to push the generated files to the live site.

9. `git checkout asf-site`
10. `rm -fr content`
11. `mv target/html content`
12. `git add content`

Verify that content/index.html and other generated files are proper, e.g. open them in a browser

13. `git status` should show modified files for the markdown that you changed
14. `git commit -m "<appropriate commit message>"`
15. `git push origin asf-site`
