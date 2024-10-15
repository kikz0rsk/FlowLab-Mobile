import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(PigeonOptions(
  dartOut: 'lib/pigeon/messages.g.dart',
  dartOptions: DartOptions(),
  kotlinOut: 'android/app/src/main/kotlin/com/danizek/dp/Messages.g.kt',
  kotlinOptions: KotlinOptions(package: 'com.danizek.dp'),
  dartPackageName: 'dp',
))
enum VpnActiveStatus { INACTIVE, ACTIVE, CONNECTING, ERROR }

class VpnStatus {
  VpnStatus(this.status);

  VpnActiveStatus status;
}

enum VpnMode { LOCAL, REMOTE }

class VpnConfiguration {
  VpnConfiguration(this.mode, this.host, this.port, this.allowedApps,
      this.caCertPath, this.clientCertPath, this.clientKeyPath);

  String host;
  int port;
  VpnMode mode;
  List<String> allowedApps;
  String caCertPath;
  String clientCertPath;
  String clientKeyPath;
}

@HostApi()
abstract class VpnApi {
  void startVpn(VpnConfiguration configuration);

  void stopVpn();

  VpnStatus getVpnStatus();
}
