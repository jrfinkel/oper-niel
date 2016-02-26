TODO:
- license info
- history?
- mention jar hell


lein-repo
=========

lein-repo is a simple and easy to use lein plugin for dependency management across different clojure projects in the same repository. Never `lein-install` again!

### Internal Dependency Management
When you have multiple clojure projects with dependencies between them in the same repo, managing those dependencies can get annoying. If you are working in library A, which depends on library B, and you need to make a change in B, then to get access to that change in A you need to `lein install` B, and then restart your repl in A. If you have a lot of clojure projects, this situation can arise multiple times a day, which is no fun. lein-repo solves this problem in a way that's as simple as adding a regular external dependency to your project.clj, while still allowing you to make changes to downstream libraries without ever restarting a repl or futzing on the command line.

### Usage

Using lein-repo is easy:

1. You must have a directory which is a (grand)*parent of all the projects; this is the root of your clojure super-project. This directory contains a file called `projects.clj`, which is a map containing three keys: 

- `required-dependencies` a seq of project.clj-style dependencies that will automatically be included with all child `project.clj`s. You *must* include lein-repo itself as a required dependency of you want to use our `lein test-all` command to run the unit tests from all internal projects.
- `external-dependencies` a seq of `project.clj`-style dependencies which child project.cljs may want to require.
- `internal-dependencies` a map from internal project name to a path to its root directory. 

Example `projects.clj`:
```clojure
{required-dependencies 
 [[org.clojure/clojure "1.7.0"]
  [prismatic/plumbing "0.5.0"]]
 external-dependencies
 [[com.climate/claypoole "1.1.1"]
  [prismatic/fnhouse "0.2.1"]]
 internal-dependencies
  {my-lib-1 "path/to/my-lib-1"
   my-lib-2 "path/to/my-lib-2"
   my-lib-3 "path/to/my-lib-3"}}
```
2. In each project's `project.clj`, instead of `:dependencies`, you now declare `:internal-dependencies`, which is a seq of internal project names, and `:external-dependencies`, which is a seq of external dependency names (name only, no version or other info). You can still use the `:dependencies` field, but we recommend against it.

3. All projects in your super-repo will now need to use the lein-repo plugin in order to be able to resolve their dependencies. We do not recommend putting the plugin in your `:user` profile, as this plugin really applies to specific projects and not specific users. If you do opt to put it in your user profile, be advised that you will need to additionally specify it for your `:uberjar` profile if you use uberjars.

Example `project.clj`:
```clojure
(defproject my-lib-1 "0.1"
  :internal-dependencies [my-lib-1 my-lib-2]
  :external-dependencies [com.climate/claypoole]
  :plugins [[lein-repo "0.3.0"]])
```

That's it! See here for an example of a mini-super-repo.

### Running all unit across all projects

To run all the unit tests from all projects with one command, simply enter the project directory for any project in the super-project, and run `lein test-all`.

### Important note on merging `project.clj`s

In almost all cases, lein-repo should work correctly right out of the box. However, if you have complicated `project.clj`s you may run into issues. Problems can occur when there are incompatibilities between the `project.clj` for the project you are in, and the `'project.clj` for an internal dependency, when the two `project.clj`s are, effectively, merged. For more common `project.clj` fields we merge them sensibly by default. For other fields, if there is only one non-nil value across all dependent `project.clj`s, then we take that value. If there are multiple non-nil values, we throw an Exception. We also provide a mechanism for specifying your own merge functions.


