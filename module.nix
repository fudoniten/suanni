packages:

{ config, lib, pkgs, ... }:

with lib;
let
  suanni-server = packages."${pkgs.system}".suanni-server;
  cfg = config.services.suanni.server;

in {
  options.services.suanni.server = with types; {
    enable = mkEnableOption "Enable Suan Ni guardian server.";

    verbose = mkEnableOption "Generate verbose logs and output.";

    event-listener = {
      hostname = mkOption {
        type = str;
        description = "Hostname of the event listener server.";
        default = "127.0.0.1";
      };

      port = mkOption {
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

    mqtt-client = {
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

      topic = mkOption {
        type = str;
        description = "MQTT topic on which to publish events.";
      };
    };
  };

  config = mkIf cfg.enable {
    systemd.services.suanni-server = {
      path = [ suanni-server ];
      wantedBy = [ "multi-user.target" ];
      after = [ "network-online.target" ];
      serviceConfig = {
        DynamicUser = true;
        LoadCredential = [
          "syno.passwd:${cfg.synology-client.password-file}"
          "mqtt.passwd:${cfg.mqtt-client.password-file}"
        ];
        ExecStart = pkgs.writeShellScript "suanni-server.sh"
          (concatStringsSep " " ([
            "suanni.server"
            "--hostname=${cfg.event-listener.hostname}"
            "--port=${toString cfg.event-listener.port}"
            "--synology-host=${cfg.synology-client.host}"
            "--synology-port=${toString cfg.synology-client.port}"
            "--synology-user=${cfg.synology-client.username}"
            "--synology-password-file=$CREDENTIALS_DIRECTORY/syno.passwd"
            "--mqtt-host=${cfg.mqtt-client.host}"
            "--mqtt-port=${toString cfg.mqtt-client.port}"
            "--mqtt-user=${cfg.mqtt-client.username}"
            "--mqtt-password-file=$CREDENTIALS_DIRECTORY/mqtt.passwd"
            "--mqtt-topic=${cfg.mqtt-client.topic}"
            "--objectifier-host=${cfg.objectifier-client.host}"
            "--objectifier-port=${toString cfg.objectifier-client.port}"
          ] ++ (optional cfg.verbose "--verbose")));
      };
    };
  };
}
