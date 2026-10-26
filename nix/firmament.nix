# Builds and checks for Firmament, used by ../flake.nix.
#
# The Clojure builds use fudo-nix-helpers (on top of clj-nix), which run
# offline from the artifacts pinned in ../deps-lock.json. Regenerate that
# file with `nix run .#update-deps` whenever deps.edn changes.
{ pkgs, helpers }:

let
  inherit (pkgs) lib;

  version = "0.1.0-SNAPSHOT";

  source = files: lib.fileset.toSource {
    root = ../.;
    fileset = lib.fileset.unions files;
  };

  referenceSrc = source [ ../reference ../test-resources ];

  pythonCheck = name: script: pkgs.runCommand name
    { nativeBuildInputs = [ pkgs.python3 ]; } ''
      cp -r ${referenceSrc} src
      chmod -R u+w src
      ${script}
      touch $out
    '';

in {
  # The library jar.
  firmament = helpers.mkClojureLib {
    name = "firmament";
    inherit version;
    src = source [ ../deps.edn ../deps-lock.json ../src ];
  };

  # clojure -M:test, including the golden vectors.
  clojure-tests = helpers.mkClojureTests {
    name = "firmament";
    src = source [ ../deps.edn ../deps-lock.json ../src ../test ../test-resources ];
  };

  reference-tests = pythonCheck "firmament-reference-tests" ''
    (cd src/reference && python3 -m unittest discover -s tests -t .)
  '';

  vectors-current = pythonCheck "firmament-vectors-current" ''
    python3 src/reference/gen_vectors.py --check
  '';

  # Locks every alias, including :test and the :build alias the helpers add.
  updateDeps = helpers.updateClojureDeps { };

  devShell = pkgs.mkShell {
    packages = [ pkgs.jdk21_headless pkgs.clojure pkgs.python3 ];
  };
}
