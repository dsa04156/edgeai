"use client";
// Adapted from jinuk/Platform-Service/flow_project/client/src/K8sNode.jsx
import React, { memo } from 'react';
import { Handle, Position } from '@xyflow/react';

// 문자열(Topic)을 예쁜 파스텔톤 색상으로 변환하는 함수
const stringToColor = (str) => {
  if (!str) return '#ccc';
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = str.charCodeAt(i) + ((hash << 5) - hash);
  }
  const h = Math.abs(hash) % 360;
  return `hsl(${h}, 70%, 85%)`; // HSL로 부드러운 배경색 생성
};

const K8sNode = memo(function K8sNode({ data }) {
  const topicColor = stringToColor(data.topic);

  return (
    <div style={{
      padding: '0',

      borderRadius: '8px',
      background: '#fff',
      boxShadow: '0 4px 6px rgba(0,0,0,0.1)',
      minWidth: '200px',
      overflow: 'hidden',
      transition: 'all 0.2s ease' // 부드러운 애니메이션
    }}>

      <Handle type="target" position={Position.Top} style={{ opacity: 0 }} />

      {/* 헤더 */}
      <div style={{
        background: data.color || '#eee',
        padding: '8px 12px',
        color: '#fff',
        fontWeight: 'bold',
        fontSize: '14px',
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center'
      }}>
        <span>{data.label}</span>
      </div>

      {/* 바디 */}
      <div style={{ padding: '12px', fontSize: '12px', color: '#444' }}>
        <div style={{marginBottom:'8px', fontWeight:'500', fontSize:'13px'}}>{data.appName}</div>

        {/* [NEW] Topic이 있다면 컬러 태그 표시 */}
        {data.topic && (
          <div style={{
            marginTop: '8px',
            background: topicColor,
            color: '#333',
            padding: '4px 8px',
            borderRadius: '12px',
            display: 'inline-block',
            fontWeight: 'bold',
            fontSize: '11px',
            border: '1px solid rgba(0,0,0,0.1)'
          }}>
             {data.topic}
          </div>
        )}

        {/* Discovery Server 포트 표시 */}
        {data.type === 'discovery' && (
          <div style={{ marginTop: '8px', background: '#e7f5ff', color: '#1c7ed6', padding: '4px 8px', borderRadius: '4px', display:'inline-block', fontWeight:'bold' }}>
             Port: {data.port || 11811}
          </div>
        )}

        {/* Script 정보 (작게) */}
        {data.script && (
          <div style={{marginTop:'8px', color:'#868e96', fontSize:'10px', fontFamily:'monospace'}}>
            {">"} {data.script}
          </div>
        )}
      </div>

      <Handle type="source" position={Position.Bottom} style={{ opacity: 0 }} />
    </div>
  );
});

export default K8sNode;
