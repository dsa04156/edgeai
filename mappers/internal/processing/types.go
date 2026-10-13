package processing

import "time"

type Input struct {
	DeviceID            string `json:"deviceId"`
	DeviceUID           string `json:"deviceUid"`
	DeviceModel         string `json:"deviceModel"`
	Namespace           string `json:"namespace"`
	Property            string `json:"property"`
	SourceGeneration    string `json:"sourceGeneration"`
	ConfigurationSHA256 string `json:"configurationSha256"`
	Type                string `json:"type"`
	Unit                string `json:"unit"`
	Broker              string `json:"broker"`
	Topic               string `json:"topic"`
	Alias               string `json:"alias"`
	Column              string `json:"column"`
	MaxAgeSeconds       int    `json:"maxAgeSeconds"`
	Reason              string `json:"reason,omitempty"`
	TargetNode          string `json:"targetNode,omitempty"`
}
type Rule struct {
	ID          string `json:"id"`
	OutputID    string `json:"outputId"`
	SQL         string `json:"sql"`
	Type        string `json:"type"`
	Unit        string `json:"unit"`
	WarmupTicks int    `json:"warmupTicks"`
	Conditional bool   `json:"conditional"`
}
type Execution struct {
	VDID            string  `json:"vdId"`
	Generation      string  `json:"generation"`
	Running         bool    `json:"running"`
	Reason          string  `json:"reason,omitempty"`
	Sources         []Input `json:"sources"`
	Rules           []Rule  `json:"rules"`
	IntervalSeconds int     `json:"intervalSeconds"`
	RetentionDays   int     `json:"retentionDays"`
}
type Plan struct {
	APIVersion     string      `json:"apiVersion"`
	EngineID       string      `json:"engineId"`
	EnginePodUID   string      `json:"enginePodUid"`
	SessionID      string      `json:"sessionId"`
	LeaseExpiresAt time.Time   `json:"leaseExpiresAt"`
	ServerTime     time.Time   `json:"serverTime"`
	Inputs         []Input     `json:"inputs"`
	Executions     []Execution `json:"executions"`
}
type Report struct {
	VDID       string   `json:"vdId"`
	Generation string   `json:"generation"`
	State      string   `json:"state"`
	Reason     string   `json:"reason"`
	RuleIDs    []string `json:"ruleIds"`
}
type Observation struct {
	APIVersion          string `json:"apiVersion"`
	Namespace           string `json:"namespace"`
	DeviceName          string `json:"deviceName"`
	Property            string `json:"property"`
	SourceGeneration    string `json:"sourceGeneration"`
	ConfigurationSHA256 string `json:"configurationSha256"`
	ObservedAt          int64  `json:"observedAt"`
	ReceivedAt          int64  `json:"receivedAt"`
	TimeBasis           string `json:"timeBasis"`
	SessionID           string `json:"sessionId"`
	Sequence            uint64 `json:"sequence"`
	Type                string `json:"type"`
	Unit                string `json:"unit"`
	Value               any    `json:"value"`
}
type Proof struct {
	Alias               string `json:"alias"`
	DeviceUID           string `json:"deviceUid"`
	SourceGeneration    string `json:"sourceGeneration"`
	ConfigurationSHA256 string `json:"configurationSha256"`
	ObservedAt          int64  `json:"observedAt"`
}
type Result struct {
	VDID         string  `json:"vdId"`
	OutputID     string  `json:"outputId"`
	Generation   string  `json:"generation"`
	Sequence     int64   `json:"sequence"`
	EvaluationID string  `json:"evaluationId"`
	EvaluationAt string  `json:"evaluationAt"`
	Value        any     `json:"value"`
	Sources      []Proof `json:"sources"`
}
