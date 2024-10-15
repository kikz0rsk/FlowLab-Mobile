import 'package:flutter/material.dart';
import 'package:get/get.dart';

class HomeScreenController extends GetxController {
  var groupValue = "remote".obs;
  var remoteServer = "192.168.1.20".obs;
  var remotePort = 20000.obs;
  var caCertPath = "".obs;
  var clientCertPath = "".obs;
  var clientKeyPath = "".obs;
  var remoteServerTextController = TextEditingController();
  var remotePortTextController = TextEditingController();
}