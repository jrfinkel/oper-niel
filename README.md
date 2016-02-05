lein-repo: a simple and easy lein plugin for dependency management across different clojure projects in the same repo [obviously too verbose right now]


When you have multiple clojure projects with dependencies between them in the same repo, managing those dependencies can get annoying. If you are working in library A, which depends on library B, and you need to make a change in B, then to get access to that change in A you need to `lein install` B, and then restart your repl in A. If you have a lot of clojure projects, this situation can arise multiple times a day, which is no fun. lein-repo solves this problem in a way that's as simple as adding a regular external dependency to your project.clj, while still allowing you to make changes to downstream libraries without ever restarting a repl or futzing on the command lein.a


Using lein-repo is easy:

1. You must have a directory which is a (grand)*parent of all the projects, the root of your clojure repo. This directory contains a file called projects.clj, which is a map containing three keys: 
required-dependencies: seq of project.clj-style dependencies that will automatically be included with all child project.cljs
external-dependencies: seq of project.clj-style dependencies which child project.cljs may want to require.
internal-dependencies: a map from internal project name to a path to its root directory. You can still use the :dependencies field, but we recommend against it.

example projects.clj

2. In each project's project.clj, instead of :dependencies, you now declare :internal-dependencies, which is a seq of internal project names, and :external-dependencies, which is a seq of external dependency names (name only, no version or other info). 

3. all projects in your super-repo will now need to use the lein-repo plugin in order to be able to resolve their dependencies. we do not recommend putting it in your :user profile, as this plugin really applies to specific projects and not specific users. if you do opt to put it in your user profile, be advised that you will need to additionally specify it for your :uberjar profile if you use uberjars.

example project.clj

That's it! See here for an example of a mini-super-repo.

