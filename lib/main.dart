import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  // This widget is the root of your application.
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'NetGuard',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.blue),
        useMaterial3: true,
      ),
      home: const HomePage(title: 'NetGuard'),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key, required this.title});

  final String title;

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  static const CHANNEL_NAME = 'com.danizek.netguard/vpn';
  static const platform = MethodChannel(CHANNEL_NAME);
  Timer? refreshTimer;
  int numReceived = 0;
  int numSent = 0;

  @override void initState() {
    super.initState();
    refreshTimer = Timer.periodic(const Duration(seconds: 2), updateData);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
        title: Text(widget.title),
      ),
      body: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: <Widget>[
          Card(
            child: Column(
              children: <Widget>[
                ListTile(
                  title: const Text('Received'),
                  subtitle: Text('$numReceived bytes'),
                ),
                ListTile(
                  title: const Text('Sent'),
                  subtitle: Text('$numSent bytes'),
                ),
              ],
            ),
          ),
          FilledButton(
            onPressed: () async {
              try {
                final result = await platform.invokeMethod<int>('start');
              } on PlatformException catch (e) {
                print('Failed to start VPN: ${e.toString()}');
              }
            }, child: const Text('Start'),
          ),
          FilledButton(
            onPressed: () async {
              try {
                final result = await platform.invokeMethod<int>('stop');
              } on PlatformException catch (e) {
                print('Failed to stop VPN: ${e.toString()}');
              }
            }, child: const Text('Stop'),
          )
        ],
      ),
    );
  }

  void updateData(Timer timer) async {
      if (!mounted) {
        return;
      }

      try {
        Int64List? result = await platform.invokeMethod<Int64List>('getData');
        print(result);
        if (result != null) {
          setState(() {
            numReceived = result[0];
            numSent = result[1];
          });
        }
        print('VPN status: $result');
      } on PlatformException catch (e) {
        print('Failed to get VPN status: ${e.toString()}');
      }
  }
}
