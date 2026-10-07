package io.edgeai.adapters.metrics;

import java.util.List;
import java.util.stream.Collectors;

/** Fixed expressions only: browser input never becomes PromQL. */
public final class PrometheusQueries {
    private PrometheusQueries() {}
    public record Metric(String id, String key, String unit, String value, String timestamp, String collector) {}

    private static Metric gauge(String id, String key, String unit, String name, double scale, String collector) {
        return new Metric(id, key, unit, "(" + name + ") * " + scale, "timestamp(" + name + ")", collector);
    }
    public static final List<Metric> METRICS = List.of(
        new Metric("cpu", "CPU_USAGE", "PERCENT",
            // irate uses only the last two samples; 2m tolerates scrape jitter, not a 2m average.
            "100 * (1 - avg by(instance,job) (irate(node_cpu_seconds_total{job=\"node-exporter\",mode=\"idle\"}[2m])))",
            "min by(instance,job) (timestamp(node_cpu_seconds_total{job=\"node-exporter\",mode=\"idle\"}))", ""),
        new Metric("memory", "MEMORY_USAGE", "PERCENT",
            "100 * (1 - node_memory_MemAvailable_bytes{job=\"node-exporter\"} / node_memory_MemTotal_bytes{job=\"node-exporter\"})",
            "timestamp(node_memory_MemAvailable_bytes{job=\"node-exporter\"}) < timestamp(node_memory_MemTotal_bytes{job=\"node-exporter\"}) or timestamp(node_memory_MemTotal_bytes{job=\"node-exporter\"})", ""),
        new Metric("cpu_temp", "CPU_TEMPERATURE", "CELSIUS",
            "max by(instance,job) (node_thermal_zone_temp{job=\"node-exporter\",type=~\"cpu-thermal|x86_pkg_temp\"})",
            "min by(instance,job) (timestamp(node_thermal_zone_temp{job=\"node-exporter\",type=~\"cpu-thermal|x86_pkg_temp\"}))", ""),
        gauge("dcgm_usage", "GPU_USAGE", "PERCENT", "DCGM_FI_DEV_GPU_UTIL", 1, ""),
        gauge("dcgm_memory", "GPU_MEMORY_USED", "BYTES", "DCGM_FI_DEV_FB_USED", 1048576, ""),
        new Metric("dcgm_total", "GPU_MEMORY_TOTAL", "BYTES", "(DCGM_FI_DEV_FB_USED + DCGM_FI_DEV_FB_FREE) * 1048576",
            "timestamp(DCGM_FI_DEV_FB_USED) < timestamp(DCGM_FI_DEV_FB_FREE) or timestamp(DCGM_FI_DEV_FB_FREE)", ""),
        gauge("dcgm_temp", "GPU_TEMPERATURE", "CELSIUS", "DCGM_FI_DEV_GPU_TEMP", 1, ""),
        gauge("dcgm_power", "GPU_POWER", "WATTS", "DCGM_FI_DEV_POWER_USAGE", 1, ""),
        gauge("jetson_usage", "GPU_USAGE", "PERCENT", "jetson_gpu_utilization_ratio", 100, "jetson_gpu_collector_success"),
        gauge("spark_usage", "GPU_USAGE", "PERCENT", "spark_gpu_utilization_percent", 1, "spark_gpu_collector_success"),
        gauge("spark_temp", "GPU_TEMPERATURE", "CELSIUS", "spark_gpu_temperature_celsius", 1, "spark_gpu_collector_success"),
        gauge("spark_power", "GPU_POWER", "WATTS", "spark_gpu_power_watts", 1, "spark_gpu_collector_success"),
        gauge("mobilint_usage", "NPU_USAGE", "PERCENT", "mobilint_npu_utilization_ratio", 100, "mobilint_npu_health"),
        gauge("mobilint_memory", "NPU_MEMORY_USED", "BYTES", "mobilint_npu_memory_used_bytes", 1, "mobilint_npu_health"),
        gauge("mobilint_total", "NPU_MEMORY_TOTAL", "BYTES", "mobilint_npu_memory_total_bytes", 1, "mobilint_npu_health"),
        gauge("mobilint_temp", "NPU_TEMPERATURE", "CELSIUS", "mobilint_npu_temperature_celsius", 1, "mobilint_npu_health"),
        gauge("mobilint_power", "NPU_POWER", "WATTS", "mobilint_npu_power_watts", 1, "mobilint_npu_health"),
        new Metric("intel_usage", "NPU_USAGE", "PERCENT", "100 * rate(hairp_npu_busy_seconds_total[1m])",
            "timestamp(hairp_npu_busy_seconds_total)", "hairp_npu_collector_success"),
        gauge("intel_memory", "NPU_MEMORY_USED", "BYTES", "hairp_npu_memory_used_bytes", 1, "hairp_npu_collector_success")
    );

    private static String tagged(String expression, String id) {
        return "label_replace((" + expression + "),\"edgeai_metric\",\"" + id + "\",\"\",\".*\")";
    }

    public static String query() {
        String targets = "up{job=~\"node-exporter|node-hardware-inventory|kube-state-metrics|dcgm-exporter|jetson-gpu-exporter|spark-gpu-exporter|kube-system/hairp-.*\"}";
        String readiness = "kube_node_status_condition{condition=\"Ready\"}";
        return METRICS.stream().map(m -> tagged(m.value(), m.id()) + " or " + tagged(m.timestamp(), m.id() + "_time"))
            .collect(Collectors.joining(" or "))
            + " or node_uname_info{job=\"node-exporter\"} or kube_node_info"
            + " or " + tagged("node_accelerator_info", "accelerator") + " or " + tagged("timestamp(node_accelerator_info)", "accelerator_time")
            + " or " + tagged("node_hardware_inventory_success", "health_node_hardware_inventory_success")
            + " or " + tagged("timestamp(node_hardware_inventory_success)", "node_hardware_inventory_success_time")
            + " or " + tagged(readiness, "node_ready") + " or " + tagged("timestamp(" + readiness + ")", "node_ready_time")
            + " or kube_pod_info{namespace=\"kube-system\"}"
            + " or " + targets + " or " + tagged("timestamp(" + targets + ")", "up_time")
            + METRICS.stream().map(Metric::collector).filter(c -> !c.isEmpty()).distinct()
                .map(c -> " or " + tagged(c, "health_" + c) + " or " + tagged("timestamp(" + c + ")", c + "_time")).collect(Collectors.joining());
    }
}
