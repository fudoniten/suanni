{
  description = "Suan Ni Home Guard";

  inputs = {
    nixpkgs.url = "nixpkgs/nixos-22.05";
    utils.url = "github:numtide/flake-utils";
    helpers = {
      url = "git+https://git.fudo.org/fudo-public/nix-helpers.git";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, utils, helpers, ... }:
    utils.lib.eachDefaultSystem (system:
      let pkgs = import nixpkgs { inherit system; };
      in {
        packages = rec {
          default = suanni-server;
          suanni-server = helpers.packages."${system}".mkClojureBin {
            name = "org.fudo/suanni.server";
            primaryNamespace = "suanni.server.cli";
            src = ./.;
          };
        };

        devShells = rec {
          default = updateDeps;
          updateDeps = pkgs.mkShell {
            buildInputs = with helpers.packages."${system}";
              [ updateClojureDeps ];
          };
          suanniServer = pkgs.mkShell {
            buildInputs = with self.packages."${system}"; [ suanni-server ];
          };
        };
      }) // {
        nixosModules = rec {
          default = suanni-server;
          suanni-server = import ./module.nix self.packages;
        };
      };
}
