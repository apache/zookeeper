## Generating the static Apache ZooKeeper website

In this directory you will find text files formatted using Markdown, with an `.md` suffix.

Building the site requires [Jekyll](http://jekyllrb.com/docs) 3.6.2 or newer. 
The easiest way to install jekyll is via a Ruby Gem. Jekyll will create a directory called `_site` 
containing `index.html` as well as the rest of the compiled directories and files. _site should not
be committed to git as this is the generated content.

To install Jekyll and its required dependencies, execute `sudo gem install jekyll pygments.rb` 
and `sudo pip install Pygments`. See the Jekyll installation page for more details.

You can generate the static ZooKeeper website by running:

1. `jekyll build` in this directory.
1. `cp -rp _released_docs _site/doc` - this will include the documentation (see "sub-dir" section below) in the generated site.

At this point the contents of _site are "staged" and can be reviewed prior to updating the ZooKeeper
production website.

During development it may be useful to include the `--watch` flag when building to have jekyll recompile
your files as you save changes. In addition to generating the site as HTML from the markdown files,
jekyll can serve the site via a web server. To build the site and run a web server use the command
`jekyll serve` which runs the web server on port 4000, then visit the site at http://localhost:4000.


## Docs sub-dir

The product documentation is not generated as part of the website. They are built separately for each release 
of ZooKeeper from the ZooKeeper source repository.

Typically during a release the versioned documentation will be recreated and should be copied, and committed,
under the "_released_docs" directory here.


## Pygments

We also use [pygments](http://pygments.org) for syntax highlighting in documentation markdown pages.

To mark a block of code in your markdown to be syntax highlighted by `jekyll` during the 
compile phase, use the following syntax:

    {% highlight java %}
    // Your code goes here, you can replace java with many other
    // supported languages too.
    {% endhighlight %}

 You probably don't need to install that unless you want to regenerate the pygments CSS file. 
 It requires Python, and can be installed by running `sudo easy_install Pygments`.