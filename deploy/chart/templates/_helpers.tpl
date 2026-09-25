{{/* The arithmetic, in one place: every derived number, from values.measured and maxmemory. */}}

{{- define "kesh.gib" -}}1073741824{{- end -}}

{{/* SAVE of a full dataset, in whole seconds, rounded up. */}}
{{- define "kesh.saveSeconds" -}}
{{- $ms := div (mul (int64 .Values.maxmemory) (int64 .Values.measured.saveMillisPerGiB)) (int64 (include "kesh.gib" .)) -}}
{{- add (div $ms 1000) 1 -}}
{{- end -}}

{{/* Loading a full snapshot, in whole seconds, rounded up. */}}
{{- define "kesh.loadSeconds" -}}
{{- $ms := div (mul (int64 .Values.maxmemory) (int64 .Values.measured.loadMillisPerGiB)) (int64 (include "kesh.gib" .)) -}}
{{- add (div $ms 1000) 1 -}}
{{- end -}}

{{/* kore's drain stage: the connections' budget, then the save, doubled for a host that is not the reference. */}}
{{- define "kesh.drainSeconds" -}}
{{- $save := ternary (int64 (include "kesh.saveSeconds" .)) 0 .Values.saveOnShutdown -}}
{{- add .Values.measured.connectionDrainSeconds (mul $save 2) 1 -}}
{{- end -}}

{{/* The grace period: announce + drain + release, and two seconds for the process to exit. */}}
{{- define "kesh.graceSeconds" -}}
{{- add .Values.measured.announceSeconds (include "kesh.drainSeconds" .) .Values.measured.releaseSeconds 2 -}}
{{- end -}}

{{/* The startup probe's budget: a full load, doubled, and ten seconds to bind. */}}
{{- define "kesh.startupSeconds" -}}
{{- add (mul (int64 (include "kesh.loadSeconds" .)) 2) 10 -}}
{{- end -}}

{{/* The memory limit in bytes: maxmemory at the peak ratio, plus the day's drift, plus the empty process. */}}
{{- define "kesh.memoryLimit" -}}
{{- $peak := div (mul (int64 .Values.maxmemory) (int64 .Values.measured.residentPeakRatioTenths)) 10 -}}
{{- $drifted := div (mul $peak (add 100 .Values.measured.dailyDriftPercent)) 100 -}}
{{- add $drifted .Values.measured.processBaseBytes -}}
{{- end -}}

{{- define "kesh.labels" -}}
app.kubernetes.io/name: kesh
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
