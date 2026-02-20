kubectl exec -it <airflow-worker-pod> -c airflow-worker -- python3 -c "
import logging

# Проверяем что настройка применилась
try:
    from airflow import settings
    settings.configure_logging()
    
    logger = logging.getLogger('airflow.task')
    print('=== airflow.task handlers ===')
    for h in logger.handlers:
        print(f'  {h.__class__.__name__}: {getattr(h, \"baseFilename\", \"stdout/stderr\")}')
    
    # Попробуем записать лог
    logger.info('TEST MESSAGE - should appear in stdout')
    print('Done. Check kubectl logs for TEST MESSAGE')
except Exception as e:
    print(f'Error: {e}')
"
-----
cat > /opt/airflow/dags/test_stdout.py << 'EOF'
from airflow import DAG
from airflow.operators.python import PythonOperator
from datetime import datetime

def hello():
    print("=== TEST LOG TO STDOUT ===")
    print("If you see this in kubectl logs, it works!")

with DAG("test_stdout", start_date=datetime(2024,1,1), schedule=None) as dag:
    PythonOperator(task_id="test_task", python_callable=hello)
EOF

# Подождать пока DAG подхватится
sleep 10

# Запустить таск напрямую (не через scheduler, просто локально)
airflow tasks test test_stdout test_task 2024-01-01
