#!/usr/bin/env python3
"""
E.D.V.I.S. MetaTrader 5 Execution Bridge for Vantage Markets
Reads a single JSON order payload from stdin or CLI args and executes it via MetaTrader5.
"""

import json
import os
import sys

try:
    import MetaTrader5 as mt5
    MT5_AVAILABLE = True
except ImportError:
    MT5_AVAILABLE = False


def execute_order(payload: dict) -> dict:
    symbol = str(payload.get("symbol", "")).upper().strip()
    action = str(payload.get("action", "")).upper().strip()
    volume = float(payload.get("volume", 0.01))
    stop_loss = float(payload.get("stop_loss", 0.0) or 0.0)
    take_profit = float(payload.get("take_profit", 0.0) or 0.0)

    login = int(os.environ.get("MT5_LOGIN", "0") or 0)
    password = os.environ.get("MT5_PASSWORD", "")
    server = os.environ.get("MT5_SERVER", "VantageInternational-Demo")
    terminal_path = os.environ.get("MT5_TERMINAL_PATH", "")
    deviation = int(os.environ.get("MT5_DEVIATION", "20") or 20)
    magic = int(os.environ.get("MT5_MAGIC_NUMBER", "990011") or 990011)

    if not MT5_AVAILABLE:
        return {
            "success": False,
            "simulated": True,
            "error": "Librería MetaTrader5 de Python no instalada. Ejecuta: pip install MetaTrader5",
            "order_preview": {
                "symbol": symbol,
                "action": action,
                "volume": volume,
                "stop_loss": stop_loss,
                "take_profit": take_profit,
                "server": server,
            },
        }

    init_kwargs = {}
    if terminal_path and os.path.exists(terminal_path):
        init_kwargs["path"] = terminal_path
    if login > 0 and password:
        init_kwargs["login"] = login
        init_kwargs["password"] = password
        init_kwargs["server"] = server

    if not mt5.initialize(**init_kwargs):
        err_code, err_str = mt5.last_error()
        return {
            "success": False,
            "error": f"Fallo al inicializar MT5 ({err_code}): {err_str}",
        }

    try:
        symbol_info = mt5.symbol_info(symbol)
        if symbol_info is None:
            return {
                "success": False,
                "error": f"Símbolo {symbol} no encontrado en Vantage MT5.",
            }

        if not symbol_info.visible:
            if not mt5.symbol_select(symbol, True):
                return {
                    "success": False,
                    "error": f"No se pudo habilitar el símbolo {symbol} en MarketWatch.",
                }

        tick = mt5.symbol_info_tick(symbol)
        if tick is None:
            return {
                "success": False,
                "error": f"Sin cotización activa (tick) para {symbol}.",
            }

        if action == "BUY":
            order_type = mt5.ORDER_TYPE_BUY
            price = tick.ask
        elif action == "SELL":
            order_type = mt5.ORDER_TYPE_SELL
            price = tick.bid
        else:
            return {"success": False, "error": f"Acción inválida: {action}"}

        request = {
            "action": mt5.TRADE_ACTION_DEAL,
            "symbol": symbol,
            "volume": volume,
            "type": order_type,
            "price": price,
            "deviation": deviation,
            "magic": magic,
            "comment": "EDVIS Tactical Execution",
            "type_time": mt5.ORDER_TIME_GTC,
            "type_filling": mt5.ORDER_FILLING_IOC,
        }

        if stop_loss > 0:
            request["sl"] = stop_loss
        if take_profit > 0:
            request["tp"] = take_profit

        result = mt5.order_send(request)
        if result is None:
            err_code, err_str = mt5.last_error()
            return {
                "success": False,
                "error": f"order_send retornó None ({err_code}): {err_str}",
            }

        if result.retcode != mt5.TRADE_RETCODE_DONE:
            return {
                "success": False,
                "retcode": result.retcode,
                "comment": result.comment,
                "error": f"Orden rechazada por broker (retcode {result.retcode}): {result.comment}",
            }

        return {
            "success": True,
            "order_ticket": result.order,
            "deal_ticket": result.deal,
            "symbol": symbol,
            "action": action,
            "volume": result.volume,
            "execution_price": result.price,
            "stop_loss": stop_loss,
            "take_profit": take_profit,
        }
    finally:
        mt5.shutdown()


if __name__ == "__main__":
    try:
        raw_input = sys.argv[1] if len(sys.argv) > 1 else sys.stdin.read()
        data = json.loads(raw_input) if raw_input.strip() else {}
        response = execute_order(data)
        print(json.dumps(response))
    except Exception as exc:
        print(json.dumps({"success": False, "error": str(exc)}))
        sys.exit(1)
