import type { AxiosInstance } from 'axios';

export abstract class BaseService {

    protected readonly http: AxiosInstance;

    constructor(http: AxiosInstance) {
        this.http = http;
    }

    protected async get<T>(url: string, config?: object): Promise<T> {
        const response = await this.http.get<T>(url, config);
        return response.data;
    }

    protected async post<T, D = unknown>(url: string, data?: D, config?: object): Promise<T> {
        const response = await this.http.post<T>(url, data, config);
        return response.data;
    }

    protected async put<T, D = unknown>(url: string, data?: D, config?: object): Promise<T> {
        const response = await this.http.put<T>(url, data, config);
        return response.data;
    }

    protected async delete<T>(url: string, config?: object): Promise<T> {
        const response = await this.http.delete<T>(url, config);
        return response.data;
    }
}