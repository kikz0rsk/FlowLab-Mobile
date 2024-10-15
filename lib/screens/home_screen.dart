import 'dart:async';
import 'dart:developer';

import 'package:dp/controllers/apps_controller.dart';
import 'package:dp/controllers/home_screen_controller.dart';
import 'package:dp/controllers/vpn_status_controller.dart';
import 'package:dp/pigeon/messages.g.dart';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:get/get.dart';
import 'package:material_symbols_icons/material_symbols_icons.dart';

import '../utils.dart';

class PieSample {
  PieSample(this.name, this.count);

  final String name;
  final int count;
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  final VpnStatusController vpnStatusController = Get.find();
  final VpnApi vpnApi = VpnApi();
  final HomeScreenController homeScreenController = Get.find();
  final AppsController appsController = Get.find();

  @override
  void initState() {
    super.initState();
    vpnStatusController.refreshTimer =
        Timer.periodic(const Duration(seconds: 2), updateData);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
        title: const Text("FlowLab"),
      ),
      body: ListView(
        // mainAxisAlignment: MainAxisAlignment.start,
        // crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          InkResponse(
            onTap: () async {
              toggleVpn();
            },
            child: Obx(
              () => Container(
                color: getBgColor(),
                child: Padding(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 30, vertical: 10),
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Obx(() => Text(
                            getVpnStatusText(),
                            style: const TextStyle(
                                fontSize: 25,
                                color: Colors.white,
                                fontWeight: FontWeight.bold),
                          )),
                      IconButton(
                        style:
                            IconButton.styleFrom(backgroundColor: Colors.white),
                        icon: Obx(() => Icon(
                              vpnStatusController.vpnActive.value !=
                                      VpnActiveStatus.INACTIVE
                                  ? Symbols.stop
                                  : Symbols.play_arrow,
                              size: 50,
                              color: vpnStatusController.vpnActive.value !=
                                      VpnActiveStatus.INACTIVE
                                  ? Colors.red
                                  : Colors.green,
                            )),
                        onPressed: () async {
                          toggleVpn();
                        },
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
          const SizedBox(height: 20),
          const Padding(
            padding: EdgeInsets.fromLTRB(15, 0, 0, 0),
            child: Text('Routed Apps', style: TextStyle(fontSize: 20)),
          ),
          Card(
            margin: const EdgeInsets.symmetric(horizontal: 10),
            child: Obx(
              () => Padding(
                padding: const EdgeInsets.all(20.0),
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.start,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(appsController.selectedApps.isEmpty
                        ? 'Currently routing every app'
                        : 'Currently routing ${appsController.selectedApps.length} apps'),
                    const SizedBox(
                      height: 10,
                    ),
                    FilledButton(
                        onPressed: vpnStatusController.vpnActive.value ==
                                VpnActiveStatus.INACTIVE
                            ? () {
                                Get.toNamed("/apps");
                              }
                            : null,
                        child: const Text("Change Routed Apps")),
                  ],
                ),
              ),
            ),
          ),
          const SizedBox(height: 20),
          const Padding(
            padding: EdgeInsets.fromLTRB(15, 0, 0, 0),
            child: Text('Remote Server', style: TextStyle(fontSize: 20)),
          ),
          Card(
            margin: const EdgeInsets.symmetric(horizontal: 10),
            child: Padding(
              padding: const EdgeInsets.all(20.0),
              child: Obx(() => Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                    children: <Widget>[
                      Text(homeScreenController.remoteServer.value.isEmpty
                          ? "No remote server set"
                          : "${homeScreenController.remoteServer.value}:${homeScreenController.remotePort.value}"),
                      const SizedBox(width: 10),
                      FilledButton(
                        onPressed: vpnStatusController.vpnActive.value ==
                                VpnActiveStatus.INACTIVE
                            ? showRemoteServerDialog
                            : null,
                        child: const Text("Change"),
                      )
                    ],
                  )),
            ),
          ),
          const SizedBox(height: 20),
          const Padding(
            padding: EdgeInsets.fromLTRB(15, 0, 0, 0),
            child: Text('Certificates', style: TextStyle(fontSize: 20)),
          ),
          Card(
            margin: const EdgeInsets.symmetric(horizontal: 10),
            child: Column(children: <Widget>[
              Obx(() => ListTile(
                  title: const Text("CA Certificate"),
                  onTap: () async {
                    String? path = await showFilePicker();
                    if (path != null) {
                      homeScreenController.caCertPath.value = path;
                    }
                  },
                  subtitle: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(homeScreenController.caCertPath.value.isEmpty
                          ? "No certificate set"
                          : homeScreenController.caCertPath.value),
                      const SizedBox(width: 10),
                    ],
                  ))),
              Obx(() => ListTile(
                  title: const Text("Client Certificate"),
                  onTap: () async {
                    String? path = await showFilePicker();
                    if (path != null) {
                      homeScreenController.clientCertPath.value = path;
                    }
                  },
                  subtitle: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(homeScreenController.clientCertPath.value.isEmpty
                          ? "No certificate set"
                          : homeScreenController.clientCertPath.value),
                      const SizedBox(width: 10),
                    ],
                  ))),
              Obx(() => ListTile(
                  title: const Text("Client Key"),
                  onTap: () async {
                    String? path = await showFilePicker();
                    if (path != null) {
                      homeScreenController.clientKeyPath.value = path;
                    }
                  },
                  subtitle: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(homeScreenController.clientKeyPath.value.isEmpty
                          ? "No certificate set"
                          : homeScreenController.clientKeyPath.value),
                      const SizedBox(width: 10),
                    ],
                  )))
            ]),
          ),
          // const Padding(
          //   padding: EdgeInsets.fromLTRB(15, 0, 0, 0),
          //   child: Text('Statistics', style: TextStyle(fontSize: 20)),
          // ),
          // Card(
          //   child: Column(
          //     children: <Widget>[
          //       ListTile(
          //         subtitle: const Text('Received'),
          //         title: Obx(() => Text(
          //             Utils.formatSize(vpnStatusController.numReceived.value))),
          //       ),
          //       ListTile(
          //         subtitle: const Text('Sent'),
          //         title: Obx(() => Text(
          //             Utils.formatSize(vpnStatusController.numSent.value))),
          //       ),
          //     ],
          //   ),
          // ),
        ],
      ),
    );
  }

  void modeChanged(String? value) {
    log("Changing mode changed to $value");
    if (value != null) {
      homeScreenController.groupValue.value = value;
      if (value == "remote") {
        showRemoteServerDialog();
      }
    }
  }

  void updateData(Timer? timer) async {
    if (!mounted) {
      return;
    }

    VpnStatus status = await vpnApi.getVpnStatus();

    vpnStatusController.vpnActive.value = status.status;
  }

  void toggleVpn() {
    if (vpnStatusController.vpnActive.value != VpnActiveStatus.INACTIVE) {
      vpnApi.stopVpn();
    } else {
      vpnApi.startVpn(VpnConfiguration(
          mode: homeScreenController.groupValue.value == "local"
              ? VpnMode.LOCAL
              : VpnMode.REMOTE,
          host: homeScreenController.remoteServer.value,
          port: homeScreenController.remotePort.value,
          allowedApps: appsController.selectedApps.toList(),
          caCertPath: homeScreenController.caCertPath.value,
          clientCertPath: homeScreenController.clientCertPath.value,
          clientKeyPath: homeScreenController.clientKeyPath.value));
    }
    updateData(null);
  }

  void showRemoteServerDialog() {
    Get.defaultDialog(
      title: "Remote Server",
      content: Column(
        children: [
          TextField(
            controller: homeScreenController.remoteServerTextController,
            decoration: const InputDecoration(labelText: "Host"),
          ),
          TextField(
            controller: homeScreenController.remotePortTextController,
            decoration: const InputDecoration(labelText: "Port"),
          ),
          FilledButton(
              onPressed: () {
                homeScreenController.remoteServer.value =
                    homeScreenController.remoteServerTextController.text;
                homeScreenController.remotePort.value = int.parse(
                    homeScreenController.remotePortTextController.text);
                Get.back();
              },
              child: const Text("Save"))
        ],
      ),
    );
  }

  Future<String?> showFilePicker() async {
    FilePickerResult? result = await FilePicker.platform.pickFiles();

    if (result != null) {
      return result.files.single.path!;
    }
    return null;
  }

  MaterialColor getBgColor() {
    switch (vpnStatusController.vpnActive.value) {
      case VpnActiveStatus.ACTIVE:
        return Colors.green;
      case VpnActiveStatus.CONNECTING:
        return Colors.yellow;
      case VpnActiveStatus.ERROR:
        return Colors.red;
      default:
        return Colors.grey;
    }
  }

  String getVpnStatusText() {
    switch (vpnStatusController.vpnActive.value) {
      case VpnActiveStatus.ACTIVE:
        return 'Active';
      case VpnActiveStatus.CONNECTING:
        return 'Connecting';
      case VpnActiveStatus.ERROR:
        return 'Error';
      default:
        return 'Inactive';
    }
  }
}
