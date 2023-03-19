packages:

{ config, lib, pkgs, ... }:

with lib;
let
  suanni-server = packages."${pkgs.system}".nexus-client;
  cfg = config.suanni.server;

in {
  options.suanni.server = with types; {
    enable = mkEnableOption "Enable Suan Ni guardian server.";

    verbose = mkEnableOption "Generate verbose logs and output.";

    event-listener = {
      hostname = mkOption {
        type = str;
        description = "Hostname of the event listener server.";
        default = "127.0.0.1";
      };

      internal-port = mkOption {
        type = port;
        description = "Port on which to listen for incoming events.";
        default = 5354;
      };
    };

    synology-client = {
      host = mkOption {
        type = str;
        description = "Hostname of the Synology server.";
      };

      port = mkOption {
        type = port;
        description =
          "Port on which to connect to the Synology server. Can be an SSL port.";
        default = 5001;
      };

      username = mkOption {
        type = str;
        description = "User as which to connect to the Synology server.";
      };

      password-file = mkOption {
        type = str;
        description =
          "File (on the local host) containing the password for the Synology server.";
      };
    };

    objectifier-client = {
      host = mkOption {
        type = str;
        description = "Hostname of the Objectifier server.";
      };

      port = mkOption {
        type = port;
        description = "Port on which the Objectifier server is listening.";
        default = 80;
      };
    };

    mqtt-server = {
      host = mkOption {
        type = str;
        description = "Hostname of the MQTT server.";
      };

      port = mkOption {
        type = port;
        description = "Port on which the MQTT server is listening.";
        default = 80;
      };

      username = mkOption {
        type = str;
        description = "User as which to connect to the MQTT server.";
      };

      password-file = mkOption {
        type = str;
        description =
          "File (on the local host) containing the password for the MQTT server.";
      };
    };
  };

  config = mkIf cfg.enable {
    services.nginx = {
      enable = true;
      recommendedOptimisations = true;
      recommendedProxySettings = true;
      recommendedGzipSettings = true;

      virtualHosts."${cfg.hostname}" = {
        locations."/".proxyPass = "http://127.0.0.1:${toString cfg.port}";
      };
    };

    systemd.suanni-server = {
      path = [ suanni-server ];
      wantedBy = [ "network-online.target" ];
      serviceConfig = {
        DynamicUser = true;
        LoadCredential = [
          "syno.passwd:${cfg.synology.password-file}"
          "mqtt.passwd:${cfg.mqtt-server.password-file}"
        ];
        ExecStart = pkgs.writeShellScript "suanni-server.sh"
          (concatStringsSep " " ([
            "suanni-server"
            "--hostname=${cfg.event-listener.hostname}"
            "--port=${toString cfg.event-listener.port}"
            "--synology-host=${cfg.synology.host}"
            "--synology-port=${toString cfg.synology.port}"
            "--synology-user=${cfg.synology.username}"
            "--synology-password-file=$CREDENTIALS_DIRECTORY/syno.passwd"
            "--mqtt-host=${cfg.mqtt.host}"
            "--mqtt-port=${toString cfg.mqtt.port}"
            "--mqtt-user=${cfg.mqtt.username}"
            "--mqtt-password-file=$CREDENTIALS_DIRECTORY/mqtt.passwd"
          ]) ++ (optional cfg.verbose "--verbose"));
      };
    };
  };
}
