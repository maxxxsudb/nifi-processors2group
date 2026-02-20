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
