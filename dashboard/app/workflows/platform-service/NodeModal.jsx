"use client";
// Adapted from jinuk/Platform-Service/flow_project/client/src/NodeModal.jsx
import React, { useState } from 'react';

const modalStyles = {
  overlay: { position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: 'rgba(0,0,0,0.5)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000 },
  content: { background: 'white', padding: '20px', borderRadius: '8px', width: '450px', maxWidth: 'calc(100vw - 32px)', maxHeight: '90vh', overflowY: 'auto', boxShadow: '0 4px 12px rgba(0,0,0,0.15)' },
  section: { marginBottom: '20px', paddingBottom: '15px', borderBottom: '1px solid #eee' },
  sectionTitle: { fontSize: '16px', fontWeight: 'bold', marginBottom: '10px', color: '#444' },
  field: { marginBottom: '12px' },
  label: { display: 'block', marginBottom: '5px', fontWeight: 'bold', fontSize: '13px', color: '#555' },
  input: { width: '100%', padding: '8px', border: '1px solid #ccc', borderRadius: '4px', fontSize: '14px', boxSizing: 'border-box' },
  select: { width: '100%', padding: '8px', border: '1px solid #ccc', borderRadius: '4px', fontSize: '14px', boxSizing: 'border-box', backgroundColor: '#fff', cursor: 'pointer' },
  readOnlyBox: { background: '#f1f3f5', padding: '8px', borderRadius: '4px', fontSize: '12px', color: '#495057', border: '1px solid #dee2e6', fontFamily: 'monospace' },
  buttonGroup: { display: 'flex', justifyContent: 'flex-end', gap: '10px', marginTop: '20px' },
  saveBtn: { background: '#228be6', color: 'white', border: 'none', padding: '8px 16px', borderRadius: '4px', cursor: 'pointer', fontWeight:'bold' },
  cancelBtn: { background: '#ccc', color: 'black', border: 'none', padding: '8px 16px', borderRadius: '4px', cursor: 'pointer' },
  // 태그 스타일
  activeTag: { display: 'flex', alignItems: 'center', background: '#e7f5ff', color: '#1864ab', padding: '6px 12px', borderRadius: '20px', marginBottom: '5px', border: '1px solid #a5d8ff', fontWeight: 'bold', fontSize: '13px', marginRight: '5px' },
  inactiveBtn: { padding: '6px 12px', background: '#f1f3f5', color: '#495057', border: '1px solid #dee2e6', borderRadius: '20px', fontSize: '12px', cursor: 'pointer', marginRight: '5px', marginBottom: '5px' },
  removeBtn: { marginLeft: '8px', color: '#fa5252', cursor: 'pointer', fontWeight: 'bold', fontSize: '14px', lineHeight: '1' }
};

// [설정] 노드 타입(이제 environment로 사용) 및 기능 라벨 목록
const ENV_TYPES = ['edge', 'cloud'];
const CAPABILITIES = ['sensor', 'camera', 'preprocessing', 'inference'];

const NodeModal = ({ node, onClose, onSave }) => {
  const [formData, setFormData] = useState(() => ({ ...node.data, nodeSelector: { ...node.data.nodeSelector, environment: node.data.nodeSelector?.environment || 'edge' } }));
  const handleChange = (e) => {
    const { name, value } = e.target;
    setFormData(prev => ({ ...prev, [name]: value }));
  };

  const updateSelector = (key, value) => {
    const newSelector = { ...formData.nodeSelector };
    if (value === null || value === 'false') {
      delete newSelector[key];
    } else {
      newSelector[key] = value;
    }
    setFormData(prev => ({ ...prev, nodeSelector: newSelector }));
  };

  const handleSubmit = () => {
    const finalSelector = { ...formData.nodeSelector };

    // [수정] environment 필수 설정
    if (!finalSelector['environment']) finalSelector['environment'] = 'edge';
    // 혹시 모를 node-type 제거
    if (finalSelector['node-type']) delete finalSelector['node-type'];

    // Capabilities 정리
    CAPABILITIES.forEach(cap => {
      if (finalSelector[cap] !== 'true') delete finalSelector[cap];
    });

    onSave(node.id, { ...formData, nodeSelector: finalSelector });
    onClose();
  };

  const isDiscovery = formData.type === 'discovery';
  const isVideoType = ['camera_pub', 'fps_display'].includes(formData.templateType);
  const isSensorType = ['sensor_pub', 'alarm_service', 'alarm_monitor'].includes(formData.templateType);
  const showTopicConfig = isVideoType || isSensorType;

  let generatedTopic = '';
  const pipeId = formData.pipelineId || 'pipe1';

  if (isVideoType) generatedTopic = `${pipeId}_video_shared`;
  else if (isSensorType) generatedTopic = formData.templateType === 'sensor_pub' ? 'SensorDataTopic' : formData.templateType === 'alarm_service' ? 'SensorDataTopic → AlarmTopic' : 'AlarmTopic';

  return (
    <div style={modalStyles.overlay}>
      <div role="dialog" aria-modal="true" aria-label="노드 설정" style={modalStyles.content} onKeyDown={e => { if(e.key === "Escape") onClose(); }}>
        <h3 style={{ marginTop: 0, borderBottom: `3px solid ${formData.color}`, paddingBottom: '10px' }}>{formData.label} Config</h3>

        {/* Basic Info */}
        <div style={modalStyles.section}>
          <div style={modalStyles.sectionTitle}>Basic Info</div>
          <div style={modalStyles.field}><label style={modalStyles.label}>Deployment Name</label><input aria-label="appName" name="appName" style={modalStyles.input} value={formData.appName || ''} onChange={handleChange} /></div>
          <div style={modalStyles.field}><label style={modalStyles.label}>Image</label><input aria-label="image" name="image" style={modalStyles.input} value={formData.image || ''} onChange={handleChange} /></div>
          <div style={modalStyles.field}><label style={modalStyles.label}>Replicas</label><input aria-label="replicas" name="replicas" type="number" style={modalStyles.input} value={formData.replicas || 1} onChange={handleChange} /></div>
        </div>

        {/* Execution Args */}
        <div style={modalStyles.section}>
          <div style={modalStyles.sectionTitle}>Execution Args</div>
          {isDiscovery ? (
             <div style={modalStyles.field}><label style={modalStyles.label}>UDP Port</label><input aria-label="port" name="port" type="number" style={modalStyles.input} value={formData.port || 11811} onChange={handleChange} /></div>
          ) : (
            <>
              {showTopicConfig && (
                <div style={{background:'#f8f9fa', padding:'10px', borderRadius:'6px', marginBottom:'15px', border:'1px solid #eee'}}>
                   <div style={{fontWeight:'bold', marginBottom:'8px', color:'#228be6'}}>Topic Generator</div>
                   <div style={{display:'flex', gap:'10px'}}>
                      <div style={{flex:1}}><label style={modalStyles.label}>Pipeline ID</label><input aria-label="pipelineId" name="pipelineId" style={modalStyles.input} value={formData.pipelineId || ''} onChange={handleChange} placeholder="pipe1" /></div>
                      <div style={{flex:1}}><label style={modalStyles.label}>Device ID</label><input aria-label="deviceId" name="deviceId" style={modalStyles.input} value={formData.deviceId || ''} onChange={handleChange} placeholder="dev1" /></div>
                   </div>
                   <div style={{marginTop:'10px'}}><label style={modalStyles.label}>Generated Topic (Preview)</label><div style={{...modalStyles.readOnlyBox, color:'#d6336c', fontWeight:'bold'}}>{generatedTopic}</div></div>
                </div>
              )}
              <div style={modalStyles.field}><label style={modalStyles.label}>Script</label><input aria-label="script" name="script" style={modalStyles.input} value={formData.script || ''} onChange={handleChange} disabled /></div>
              {formData.templateType === 'camera_pub' && (<div style={modalStyles.field}><label style={modalStyles.label}>RTSP URL</label><input aria-label="rtspUrl" name="rtspUrl" style={modalStyles.input} value={formData.rtspUrl || ''} onChange={handleChange} /></div>)}
              {!showTopicConfig && (<div style={modalStyles.field}><label style={modalStyles.label}>Arguments</label><input aria-label="args" name="args" style={modalStyles.input} value={formData.args || ''} onChange={handleChange} /></div>)}
            </>
          )}
        </div>

        {/* Node Selector (Scheduling) */}
        <div style={{...modalStyles.section, borderBottom:'none'}}>
          <div style={modalStyles.sectionTitle}>Node Selector (Scheduling)</div>

          {/* [수정] node-type 대신 environment 선택 */}
          <div style={modalStyles.field}>
            <label style={modalStyles.label}>environment (Edge/Cloud)</label>
            <select aria-label="environment"
              style={modalStyles.select}
              value={formData.nodeSelector?.['environment'] || 'edge'}
              onChange={(e) => updateSelector('environment', e.target.value)}
            >
              {ENV_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
            </select>
          </div>

          <div style={modalStyles.label}>Active Capabilities</div>
          <div style={{display:'flex', flexWrap:'wrap', marginBottom:'15px', minHeight:'30px'}}>
            {CAPABILITIES.map((cap) => {
              if (formData.nodeSelector?.[cap] === 'true') {
                return (
                  <div key={cap} style={modalStyles.activeTag}>
                    <span>{cap}</span>
                    <span style={modalStyles.removeBtn} onClick={() => updateSelector(cap, null)}>×</span>
                  </div>
                );
              }
              return null;
            })}
            {CAPABILITIES.every(cap => formData.nodeSelector?.[cap] !== 'true') && (
              <span style={{fontSize:'12px', color:'#ccc', fontStyle:'italic', padding:'5px 0'}}>No active capabilities selected.</span>
            )}
          </div>

          <div style={{borderTop:'1px dashed #eee', paddingTop:'10px'}}>
            <label style={{...modalStyles.label, color:'#999', fontSize:'12px'}}>Add Capabilities:</label>
            <div style={{display:'flex', flexWrap:'wrap'}}>
              {CAPABILITIES.map((cap) => {
                if (formData.nodeSelector?.[cap] !== 'true') {
                  return (<button key={cap} style={modalStyles.inactiveBtn} onClick={() => updateSelector(cap, 'true')}>+ {cap}</button>);
                }
                return null;
              })}
            </div>
          </div>
        </div>

        <div style={modalStyles.buttonGroup}><button style={modalStyles.cancelBtn} onClick={onClose}>Cancel</button><button style={modalStyles.saveBtn} onClick={handleSubmit}>Save</button></div>
      </div>
    </div>
  );
};

export default NodeModal;
