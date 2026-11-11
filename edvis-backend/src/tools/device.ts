import { WebSocket } from 'ws';

export interface DeviceActionInput {
  action_type: 'REPLY_WHATSAPP' | 'MAKE_PHONE_CALL' | 'OPEN_APP';
  target: string;
  payload?: string;
}

export function dispatchDeviceAction(
  ws: WebSocket,
  input: DeviceActionInput
): { dispatched: boolean; command_id: string; details: DeviceActionInput } {
  const commandId = `cmd-${Date.now()}`;

  const packet = {
    event: 'device_action',
    command_id: commandId,
    action_type: input.action_type,
    target: input.target,
    payload: input.payload || '',
    timestamp: new Date().toISOString(),
  };

  if (ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(packet));
    return {
      dispatched: true,
      command_id: commandId,
      details: input,
    };
  }

  return {
    dispatched: false,
    command_id: commandId,
    details: input,
  };
}
