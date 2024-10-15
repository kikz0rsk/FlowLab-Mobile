import 'package:dp/controllers/apps_controller.dart';
import 'package:dp/controllers/home_screen_controller.dart';
import 'package:dp/controllers/vpn_status_controller.dart';
import 'package:dp/screens/apps_screen.dart';
import 'package:dp/screens/home_screen.dart';
import 'package:flutter/material.dart';
import 'package:get/get.dart';

final routes = [
  GetPage(name: "/", page: () => const HomePage()),
  GetPage(name: "/apps", page: () => AppsScreen()),
];

void main() {
  runApp(FlowLabApp());
}

class FlowLabApp extends StatelessWidget {
  FlowLabApp({super.key});

  final VpnStatusController vpnStatusController =
      Get.put(VpnStatusController());
  final AppsController appsController = Get.put(AppsController());
  final HomeScreenController homeScreenController = Get.put(HomeScreenController());

  @override
  Widget build(BuildContext context) {
    return GetMaterialApp(
      title: 'FlowLab',
      getPages: routes,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.blue),
        useMaterial3: true,
      ),
      home: const HomePage(),
    );
  }
}

