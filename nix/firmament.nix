# Builds and checks for Firmament, used by ../flake.nix.
#
# The Clojure builds use fudo-nix-helpers (on top of clj-nix), which run
# offline from the artifacts pinned in ../deps-lock.json. Regenerate that
# file with `nix run .#update-deps` whenever deps.edn changes.
{ pkgs, helpers }:

let
  inherit (pkgs) lib;

  # mkClojureLib passes the version to `clojure -T:build` unquoted, where it is
  # read as EDN, so it must read back as itself: "0.1" works, "0.1.0" doesn't.
  version = "0.1";

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

  libSrc = source [ ../deps.edn ../deps-lock.json ../src ];

in rec {
  # The `firmament` command.
  firmament = helpers.mkClojureBin {
    name = "firmament";
    primaryNamespace = "firmament.cli";
    inherit version;
    src = libSrc;
  };

  # The library jar.
  firmament-lib = helpers.mkClojureLib {
    name = "firmament";
    inherit version;
    src = libSrc;
  };

  # Put a file through the built command and read it back.
  cli-smoke = pkgs.runCommand "firmament-cli-smoke"
    { nativeBuildInputs = [ firmament ]; } ''
      export FIRMAMENT_STORE=$PWD/store
      head -c 1000000 /dev/urandom > big
      printf 'hello\n' > small
      firmament put small big > refs
      expected="sha256:$({ printf 'blob\n'; cat small; } | sha256sum | cut -d' ' -f1)"
      [ "$(head -n1 refs)" = "$expected" ]
      firmament cat "$(sed -n 2p refs)" | cmp - big
      touch $out
    '';

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
