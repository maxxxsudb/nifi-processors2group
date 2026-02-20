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
cat > /opt/airflow/dags/test_dag.py << 'EOF'
from airflow import DAG
from airflow.operators.python import PythonOperator
from datetime import datetime

def hello():
    print("=== TEST LOG TO STDOUT ===")

with DAG("test_dag", start_date=datetime(2024,1,1), schedule=None) as dag:
    PythonOperator(task_id="test_task", python_callable=hello)
EOF

# Теперь dag_id = test_dag, task_id = test_task
airflow tasks test test_dag test_task 2024-01-01 --subdir /opt/airflow/dags/test_dag.py
