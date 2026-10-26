{
  description = "Firmament: content-addressed computing, starting with Clojure";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-26.05";
    fudo-nix-helpers = {
      url = "github:fudoniten/fudo-nix-helpers";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, fudo-nix-helpers }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system:
        f (import ./nix/firmament.nix {
          pkgs = nixpkgs.legacyPackages.${system};
          helpers = fudo-nix-helpers.legacyPackages.${system};
        }));
    in {
      # nix build; nix run . -- put FILE
      packages = forAllSystems (f: {
        default = f.firmament;
        inherit (f) firmament firmament-lib;
      });

      # nix flake check
      checks = forAllSystems (f: {
        inherit (f) firmament firmament-lib cli-smoke clojure-tests reference-tests
          vectors-current;
      });

      # nix run .#update-deps    (after changing deps.edn)
      apps = forAllSystems (f: {
        update-deps = {
          type = "app";
          program = "${f.updateDeps}/bin/update-deps.sh";
        };
      });

      # nix develop
      devShells = forAllSystems (f: { default = f.devShell; });
    };
}
