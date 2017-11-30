## The production Apache ZooKeeper website

This branch houses the production Apache ZooKeeper website.

Updating this branch and pushing to the Apache git repo will cause gitpubsub to publish the site - BE CAREFULL!

## How to update

Typically the website will need to be updated as part of a release. Prior to updating this branch
update the "website" branch appropriately, "jekyl build", update the product docs, APIs, etc... and
then reviewed the staged site as detailed in the website branch README.

After verifying the staged site you can:

1. `git checkout asf-site` - checkout this branch
1. `rm -fr content` - remove the old site content
1. `mv _site content` - rename the staged site directory. You should still have this from the prior staging efforts
1. review the changes and verify that they are what you expect
1. `git add .` - update git appropriately (may include git rm, etc..., as well).
1. `git commit` - ensure an good commit message
1. WARNING: review the changes you've made to git and as the next step will cause the live production site to be updated
1. `git push <origin> asf-site` - point of no return.

At this point you can verify that the production site is updated and the links, etc... are functioning properly.
