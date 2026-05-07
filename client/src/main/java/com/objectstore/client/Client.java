package com.objectstore.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * CLI entry point for the distributed object store client.
 *
 * Supports two modes:
 *   1. Simple mode (upload/download/demo): erasure coding + FPCC integrity
 *   2. AVID-FP mode (avid-disperse/avid-retrieve): full server-to-server consensus protocol
 */
public class Client {

    public static void main(String[] args) {
        if (args.length < 1) { printUsage(); System.exit(1); }

        String cmd = args[0].toLowerCase();

        try {
            switch (cmd) {

                // -- Simple erasure coding with FPCC --

                case "upload" -> {
                    // upload <file> <fileId> <node1:port> ... <node6:port>
                    int n = LinearErasureCodec.TOTAL_FRAGMENTS;
                    requireArgs(args, 3 + n, "upload");
                    Path file     = Paths.get(args[1]);
                    String fileId = args[2];
                    List<ErasureClient.NodeAddress> nodes = parseNodes(args, 3, n);

                    ErasureClient ec = new ErasureClient();
                    try {
                        ShardManifest manifest = ec.upload(file, fileId, nodes);
                        Path manifestPath = Paths.get(fileId + ".manifest");
                        manifest.save(manifestPath);
                        System.out.printf("Manifest saved → %s%n", manifestPath.toAbsolutePath());
                    } finally {
                        ec.shutdown();
                    }
                }

                case "download" -> {
                    // download <fileId> <outputFile> [<node1:port> ... <node6:port>]
                    requireArgs(args, 3, "download");
                    String fileId   = args[1];
                    Path outputPath = Paths.get(args[2]);

                    Path savedManifest = Paths.get(fileId + ".manifest");
                    ShardManifest manifest;
                    if (Files.exists(savedManifest)) {
                        System.out.printf("Loading manifest from %s%n", savedManifest.toAbsolutePath());
                        manifest = ShardManifest.load(savedManifest);
                    } else {
                        int n = LinearErasureCodec.TOTAL_FRAGMENTS;
                        requireArgs(args, 3 + n, "download (no manifest found)");
                        List<ErasureClient.NodeAddress> nodes = parseNodes(args, 3, n);
                        manifest = new ShardManifest(fileId);
                        manifest.setRsMetadata(LinearErasureCodec.SOURCE_FRAGMENTS, n, 0L);
                        for (var node : nodes) manifest.addNodeAddress(node.toHostPort());
                    }

                    ErasureClient ec = new ErasureClient();
                    try {
                        ec.download(fileId, manifest, outputPath);
                    } finally {
                        ec.shutdown();
                    }
                }

                case "demo" -> {
                    // demo <file> <fileId> <node1:port> ... <node6:port>
                    int n = LinearErasureCodec.TOTAL_FRAGMENTS;
                    requireArgs(args, 3 + n, "demo");
                    Path file     = Paths.get(args[1]);
                    String fileId = args[2];
                    List<ErasureClient.NodeAddress> nodes = parseNodes(args, 3, n);

                    byte[] original = Files.readAllBytes(file);

                    ErasureClient ec = new ErasureClient();
                    try {
                        ShardManifest manifest = ec.upload(file, fileId, nodes);
                        manifest.save(Paths.get(fileId + ".manifest"));

                        Path recovered = file.resolveSibling(file.getFileName() + ".recovered");
                        ec.download(fileId, manifest, recovered);

                        byte[] recoveredBytes = Files.readAllBytes(recovered);
                        boolean match = Arrays.equals(original, recoveredBytes);
                        System.out.println();
                        System.out.println(match
                                ? "✅  DEMO SUCCESS — recovered bytes match the original."
                                : "❌  DEMO FAILURE — recovered bytes differ!");
                        System.exit(match ? 0 : 1);
                    } finally {
                        ec.shutdown();
                    }
                }

                // -- AVID-FP: server-to-server consensus protocol (paper Section 4) --

                case "avid-disperse" -> {
                    int n = LinearErasureCodec.TOTAL_FRAGMENTS;
                    requireArgs(args, 3 + n, "avid-disperse");
                    Path file     = Paths.get(args[1]);
                    String fileId = args[2];
                    List<ErasureClient.NodeAddress> nodes = parseNodes(args, 3, n);

                    AvidFpClient avid = new AvidFpClient();
                    try { avid.disperse(file, fileId, nodes); }
                    finally { avid.shutdown(); }
                }

                case "avid-retrieve" -> {
                    int n = LinearErasureCodec.TOTAL_FRAGMENTS;
                    requireArgs(args, 4 + n, "avid-retrieve");
                    String fileId   = args[1];
                    long origLen    = Long.parseLong(args[2]);
                    Path outputPath = Paths.get(args[3]);
                    List<ErasureClient.NodeAddress> nodes = parseNodes(args, 4, n);

                    AvidFpClient avid = new AvidFpClient();
                    try { avid.retrieve(fileId, nodes, origLen, outputPath); }
                    finally { avid.shutdown(); }
                }

                default -> {
                    System.err.println("Unknown command: " + cmd);
                    printUsage();
                    System.exit(1);
                }
            }
        } catch (NumberFormatException e) {
            System.err.println("Error: port must be an integer. " + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void requireArgs(String[] args, int required, String subCmd) {
        if (args.length < required) {
            System.err.println("'" + subCmd + "' requires " + (required - 1) + " arguments.");
            printUsage();
            System.exit(1);
        }
    }

    private static List<ErasureClient.NodeAddress> parseNodes(String[] args, int offset, int count) {
        List<ErasureClient.NodeAddress> nodes = new ArrayList<>(count);
        for (int i = offset; i < offset + count; i++)
            nodes.add(ErasureClient.NodeAddress.parse(args[i]));
        return nodes;
    }

    private static void printUsage() {
        System.err.println("""
                Usage:
                  # Erasure coding with FPCC integrity (6 nodes by default)
                  java -jar client.jar upload   <file> <fileId> <n1:p1> ... <n6:p6>
                  java -jar client.jar download <fileId> <outputFile> [<n1:p1> ... <n6:p6>]
                  java -jar client.jar demo     <file> <fileId> <n1:p1> ... <n6:p6>

                  # AVID-FP: server-to-server consensus (paper Section 4)
                  java -jar client.jar avid-disperse <file> <fileId> <n1:p1> ... <n6:p6>
                  java -jar client.jar avid-retrieve <fileId> <originalLen> <outputFile> <n1:p1> ... <n6:p6>

                Example:
                  java -jar client.jar demo myfile.txt myfile \\
                    localhost:7100 localhost:7101 localhost:7102 \\
                    localhost:7103 localhost:7104 localhost:7105
                """);
    }
}
